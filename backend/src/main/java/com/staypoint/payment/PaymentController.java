package com.staypoint.payment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.staypoint.common.StaypointProperties;
import com.staypoint.common.error.BusinessException;
import com.staypoint.common.error.ErrorCode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
public class PaymentController {

	static final String WEBHOOK_SECRET_HEADER = "X-Mock-PG-Secret";

	private final PaymentService paymentService;
	private final StaypointProperties properties;

	public PaymentController(PaymentService paymentService, StaypointProperties properties) {
		this.paymentService = paymentService;
		this.properties = properties;
	}

	/**
	 * 결제 요청. Idempotency-Key 는 클라이언트가 결제 화면에 들어올 때 만들어 재시도에도 같은 값을 보낸다.
	 * failRate · delayMs 는 데모용 장애 주입 값으로 모의 PG 에 그대로 전달한다.
	 */
	@PostMapping("/api/reservations/{reservationId}/payments")
	public PaymentResponse pay(@PathVariable Long reservationId,
			@RequestHeader("X-User-Id") String userId,
			@RequestHeader("Idempotency-Key") String idempotencyKey,
			@RequestParam(required = false) Double failRate,
			@RequestParam(required = false) Long delayMs) {
		return paymentService.pay(reservationId, userId, idempotencyKey, failRate, delayMs);
	}

	public record WebhookRequest(@NotBlank String orderId, @NotBlank String status, String tid, BigDecimal amount) {
	}

	/** 모의 PG 승인 통지. 공유 비밀값 헤더가 맞아야 받는다 (아무나 "승인됐다" 고 보내 예약을 확정시키지 못하게). */
	@PostMapping("/api/payments/webhook")
	public void webhook(@RequestHeader(value = WEBHOOK_SECRET_HEADER, required = false) String secret,
			@Valid @RequestBody WebhookRequest request) {
		if (!secretMatches(secret)) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED, "웹훅 비밀값이 올바르지 않습니다.");
		}
		paymentService.handleWebhook(request.orderId(), request.status(), request.tid(), request.amount());
	}

	private boolean secretMatches(String given) {
		String expected = properties.pg().webhookSecret();
		if (expected == null || expected.isBlank() || given == null) {
			return false; // 비밀값이 설정되지 않았으면 모든 통지를 거부
		}
		// 상수 시간 비교 (응답 시간 차이로 비밀값을 추측하지 못하게)
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
	}
}
