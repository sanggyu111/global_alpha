package com.staypoint.mockpg;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.staypoint.common.error.BusinessException;
import com.staypoint.common.error.ErrorCode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 모의 PG (설계 5장). 같은 앱 안에 있지만 우리 서비스는 반드시 HTTP 로 호출한다
 * → 지연·타임아웃·5xx 가 실제 외부 PG 처럼 네트워크 너머에서 일어난다.
 * payment 패키지와 코드 의존이 없고 자기 테이블(mockpg_*)만 쓴다.
 */
@RestController
@RequestMapping("/mock-pg/payments")
public class MockPgController {

	private static final long MAX_DELAY_MS = 30_000;
	private static final int MAX_FAIL_TIMES = 10;

	private final MockPgService service;
	private final MockPgWebhookSender webhookSender;
	private final MockPgProperties properties;

	MockPgController(MockPgService service, MockPgWebhookSender webhookSender, MockPgProperties properties) {
		this.service = service;
		this.webhookSender = webhookSender;
		this.properties = properties;
	}

	public record ApproveRequest(
			@NotBlank @Size(max = 50) String orderId,
			@NotNull @DecimalMin("1") BigDecimal amount) {
	}

	public record PaymentResponse(String orderId, String status, String tid, BigDecimal amount,
			BigDecimal canceledAmount) {

		static PaymentResponse from(MockPgPayment p) {
			return new PaymentResponse(p.orderId(), p.status(), p.tid(), p.amount(), p.canceledAmount());
		}
	}

	public record CancelRequest(
			@NotBlank @Size(max = 100) String cancelKey,
			@NotNull @DecimalMin("1") BigDecimal amount) {
	}

	public record CancelResponse(String cancelKey, String tid, BigDecimal cancelAmount,
			BigDecimal canceledAmount, BigDecimal remainingAmount, String status) {
	}

	/**
	 * 승인. 응답 status 는 APPROVED / FAILED. 같은 orderId 재요청은 처음 결과를 그대로 돌려준다.
	 * 지연은 승인을 기록한 "뒤" 에 넣는다 → 호출한 쪽이 타임아웃 나도 PG 에는 승인이 남는 상황을 재현.
	 */
	@PostMapping("/approve")
	public PaymentResponse approve(@Valid @RequestBody ApproveRequest request,
			@RequestParam(required = false) Double failRate,
			@RequestParam(required = false) Long delayMs) {
		double rate = rate("failRate", failRate, properties.approve().failRate());
		long delay = delay(delayMs, properties.approve().delayMs());

		MockPgService.ApproveOutcome outcome = service.approve(request.orderId(), request.amount(), rate);
		if (outcome.created()) {
			webhookSender.sendAsync(outcome.payment()); // 트랜잭션 커밋 후라 통지를 받은 쪽이 바로 조회해도 보인다
		}
		sleep(delay);
		return PaymentResponse.from(outcome.payment());
	}

	/** 승인 기록 조회. 응답을 못 받은 결제의 최종 상태를 확인할 때 쓴다 (설계 4.4 타임아웃). */
	@GetMapping("/{orderId}")
	public PaymentResponse find(@PathVariable String orderId) {
		return PaymentResponse.from(service.find(orderId));
	}

	/**
	 * 전액·부분 취소. failRate 확률로 503 을 돌려주며 이때는 아무것도 기록하지 않는다 (재시도 대상).
	 * failTimes·failType(데모, T20): 이 결제의 취소를 지금부터 failTimes 번 실패시킨다. 이후 요청에 파라미터가
	 * 없어도 남은 횟수만큼 실패하므로 재시도 스케줄러에도 같은 장애가 이어진다.
	 */
	@PostMapping("/{tid}/cancel")
	public CancelResponse cancel(@PathVariable String tid, @Valid @RequestBody CancelRequest request,
			@RequestParam(required = false) Double failRate,
			@RequestParam(required = false) Integer failTimes,
			@RequestParam(defaultValue = "UNAVAILABLE") MockPgCancelFault failType) {
		if (failTimes != null && (failTimes < 0 || failTimes > MAX_FAIL_TIMES)) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED, "failTimes 는 0~" + MAX_FAIL_TIMES + " 사이여야 합니다.",
					Map.of("failTimes", failTimes));
		}
		Optional<MockPgCancelFault> fault = service.takeCancelFault(tid, failTimes, failType);
		if (fault.isPresent()) {
			throw new BusinessException(fault.get() == MockPgCancelFault.REJECTED ? ErrorCode.PG_REJECTED
					: ErrorCode.PG_UNAVAILABLE);
		}
		double rate = rate("failRate", failRate, properties.cancel().failRate());
		if (ThreadLocalRandom.current().nextDouble() < rate) {
			throw new BusinessException(ErrorCode.PG_UNAVAILABLE);
		}
		MockPgService.CancelOutcome outcome = service.cancel(tid, request.cancelKey(), request.amount());
		MockPgPayment p = outcome.payment();
		return new CancelResponse(request.cancelKey(), tid, outcome.cancelAmount(),
				p.canceledAmount(), p.remainingAmount(), p.status());
	}

	private static double rate(String name, Double requested, double fallback) {
		double value = requested != null ? requested : fallback;
		if (value < 0 || value > 1) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED, name + " 는 0~1 사이여야 합니다.", Map.of(name, value));
		}
		return value;
	}

	private static long delay(Long requested, long fallback) {
		long value = requested != null ? requested : fallback;
		if (value < 0 || value > MAX_DELAY_MS) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED, "delayMs 는 0~" + MAX_DELAY_MS + " 사이여야 합니다.",
					Map.of("delayMs", value));
		}
		return value;
	}

	private static void sleep(long millis) {
		if (millis == 0) {
			return;
		}
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
