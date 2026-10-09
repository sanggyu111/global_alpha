package com.staypoint.payment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import com.staypoint.common.StaypointProperties;
import com.staypoint.common.error.BusinessException;
import com.staypoint.common.error.ErrorCode;
import com.staypoint.payment.PgClient.PgApproval;
import com.staypoint.payment.PgClient.PgUnavailableException;

/**
 * 결제 흐름 조립 (설계 4.4). 이 클래스에는 @Transactional 을 걸지 않는다:
 * TX1(PaymentProcessor.prepare) 커밋 → PG 호출(트랜잭션 밖) → TX2(applyResult) 순서로,
 * PG 를 기다리는 동안 DB 잠금·커넥션을 쥐지 않는다.
 */
@Service
public class PaymentService {

	static final String ACTOR_SYNC = "system:payment";
	static final String ACTOR_WEBHOOK = "system:pg-webhook";
	static final String ACTOR_RECONCILE = "system:payment-reconcile";

	private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

	private final PaymentProcessor processor;
	private final PaymentCancelExecutor cancelExecutor;
	private final PaymentRepository paymentRepository;
	private final PgClient pgClient;
	private final StaypointProperties properties;
	private final Clock clock;

	public PaymentService(PaymentProcessor processor, PaymentCancelExecutor cancelExecutor,
			PaymentRepository paymentRepository, PgClient pgClient, StaypointProperties properties, Clock clock) {
		this.processor = processor;
		this.cancelExecutor = cancelExecutor;
		this.paymentRepository = paymentRepository;
		this.pgClient = pgClient;
		this.properties = properties;
		this.clock = clock;
	}

	/** 결제 요청. failRate·delayMs 는 데모용 장애 주입 값 (모의 PG 에 전달). */
	public PaymentResponse pay(Long reservationId, String userId, String idempotencyKey, Double failRate, Long delayMs) {
		if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key 헤더는 1~100자여야 합니다.",
					Map.of("header", "Idempotency-Key"));
		}
		PaymentProcessor.Prepared prepared = processor.prepare(reservationId, userId, idempotencyKey);

		if (prepared.needsPgCall()) {
			try {
				PgApproval result = pgClient.approve(prepared.orderId(), prepared.amount(), failRate, delayMs);
				apply(prepared.orderId(), result, ACTOR_SYNC);
			} catch (PgUnavailableException e) {
				// 결과를 모른다 — PG 에서는 승인됐을 수 있으므로 실패로 단정하지 않고 READY 로 둔다.
				// 웹훅 또는 상태 확정 스케줄러가 확정하고, 화면에는 PROCESSING 으로 알린다.
				log.warn("PG 승인 응답 없음 → PROCESSING: orderId={}, {}", prepared.orderId(), e.getMessage());
			}
		}
		return processor.currentResult(prepared.paymentId());
	}

	/** 모의 PG 승인 통지. 같은 통지가 여러 번 와도 applyResult 가 한 번만 반영한다. */
	public void handleWebhook(String orderId, String status, String tid, BigDecimal amount) {
		PgApproval result = "FAILED".equals(status)
				? PgApproval.declined("PG 승인 거절")
				: new PgApproval(true, tid, amount, null);
		apply(orderId, result, ACTOR_WEBHOOK);
	}

	/**
	 * 응답을 못 받고 READY 로 오래 남은 결제를 PG 조회로 확정한다 (설계 4.4 타임아웃).
	 * PG 에 기록이 없으면 승인 요청이 PG 에 닿지 않은 것이므로 실패 처리한다.
	 *
	 * @return 확인한 결제 수
	 */
	public int reconcileStaleReady() {
		StaypointProperties.PaymentReconcile config = properties.paymentReconcile();
		Instant before = clock.instant().minus(Duration.ofSeconds(config.readyThresholdSeconds()));
		List<String> orderIds = paymentRepository.findOrderIdsByStatusCreatedBefore(PaymentStatus.READY, before,
				Limit.of(config.batchSize()));
		int checked = 0;
		for (String orderId : orderIds) {
			try {
				Optional<PgApproval> found = pgClient.find(orderId);
				apply(orderId, found.orElse(PgApproval.declined("PG 승인 기록 없음")), ACTOR_RECONCILE);
				checked++;
			} catch (RuntimeException e) {
				log.warn("결제 상태 확정 실패 — 다음 주기에 재시도: orderId={}, {}", orderId, e.getMessage());
			}
		}
		return checked;
	}

	private void apply(String orderId, PgApproval result, String actor) {
		PaymentProcessor.Applied applied = processor.applyResult(orderId, result, actor);
		if (applied.compensationCancelId() != null) {
			// 보상 취소 요청은 이미 커밋됨 → 지금 1회 시도. 실패해도 기록이 남아 재시도(T14) 대상이 된다.
			cancelExecutor.attempt(applied.compensationCancelId());
		}
	}
}
