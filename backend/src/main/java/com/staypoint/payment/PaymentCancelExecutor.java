package com.staypoint.payment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.staypoint.common.StaypointProperties;
import com.staypoint.payment.PgClient.PgRejectedException;
import com.staypoint.payment.PgClient.PgUnavailableException;

/**
 * payment_cancel 한 건을 PG 에 1회 시도한다 (설계 4.6). 보상 취소 직후 즉시 시도에 쓰고,
 * 재시도 스케줄러(T14)도 같은 메서드를 쓴다.
 *
 * <p>PG 호출을 사이에 두고 짧은 트랜잭션 두 개로 나눈다: ① 대상 읽기 → (트랜잭션 밖) PG 취소 → ② 결과 반영.
 * 경계를 한 메서드에서 보이게 하려고 TransactionTemplate 을 쓴다.
 * 같은 건을 두 곳에서 동시에 시도해도 PG 는 cancel_key 로 한 번만 취소하고, ②는 PENDING 일 때만 반영한다.
 */
@Component
public class PaymentCancelExecutor {

	private static final Logger log = LoggerFactory.getLogger(PaymentCancelExecutor.class);

	private final PaymentCancelRepository cancelRepository;
	private final PaymentRepository paymentRepository;
	private final PgClient pgClient;
	private final TransactionTemplate tx;
	private final StaypointProperties properties;
	private final Clock clock;

	public PaymentCancelExecutor(PaymentCancelRepository cancelRepository, PaymentRepository paymentRepository,
			PgClient pgClient, PlatformTransactionManager transactionManager, StaypointProperties properties,
			Clock clock) {
		this.cancelRepository = cancelRepository;
		this.paymentRepository = paymentRepository;
		this.pgClient = pgClient;
		this.tx = new TransactionTemplate(transactionManager);
		this.properties = properties;
		this.clock = clock;
	}

	private record Target(Long paymentId, String tid, String cancelKey, BigDecimal amount) {
	}

	public void attempt(Long cancelId) {
		Target target = tx.execute(status -> {
			PaymentCancel cancel = cancelRepository.findById(cancelId).orElseThrow();
			if (cancel.getStatus() != PaymentCancel.Status.PENDING) {
				return null;
			}
			Payment payment = paymentRepository.findById(cancel.getPaymentId()).orElseThrow();
			return new Target(payment.getId(), payment.getPgTid(), cancel.getCancelKey(), cancel.getCancelAmount());
		});
		if (target == null) {
			return;
		}

		String error = null;
		boolean retryable = true;
		try {
			pgClient.cancel(target.tid(), target.cancelKey(), target.amount());
		} catch (PgUnavailableException e) {
			error = e.getMessage();
		} catch (PgRejectedException e) {
			error = e.getMessage();
			retryable = false; // 다시 보내도 같은 거절 → 바로 운영자 확인 대상
		}

		String failure = error;
		boolean canRetry = retryable;
		tx.executeWithoutResult(status -> {
			Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
			// 잠금 순서: payment → payment_cancel
			Payment payment = paymentRepository.findByIdForUpdate(target.paymentId()).orElseThrow();
			PaymentCancel cancel = cancelRepository.findByIdForUpdate(cancelId).orElseThrow();
			if (cancel.getStatus() != PaymentCancel.Status.PENDING) {
				return; // 다른 시도가 먼저 반영함
			}
			if (failure == null) {
				cancel.succeed(now);
				payment.applyCancel(cancel.getCancelAmount(), now);
				return;
			}
			cancel.recordFailure(failure, canRetry, properties.cancelRetry().maxAttempts(), now);
			log.warn("PG 취소 실패: cancelId={}, 시도 {}회, 상태 {}, {}", cancelId, cancel.getAttemptCount(),
					cancel.getStatus(), failure);
		});
	}
}
