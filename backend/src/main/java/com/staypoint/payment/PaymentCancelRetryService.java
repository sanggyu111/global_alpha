package com.staypoint.payment;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.staypoint.common.StaypointProperties;
import com.staypoint.common.error.BusinessException;
import com.staypoint.common.error.ErrorCode;

/**
 * 결제 취소 재시도 (설계 4.6, FR-RTY-1~4).
 *
 * <p>PG 호출은 행 잠금을 쥔 채 하지 않는다:
 * ① 짧은 트랜잭션으로 대상 행을 잠가 "처리 중" 표시(next_retry_at 을 미룸) 후 커밋 →
 * ② 건마다 PaymentCancelExecutor 가 PG 취소 1회 시도 · 결과 반영.
 * 실패 시 백오프(1·2·4·8분) 후 다시 대상이 되고, 최대 횟수에 닿으면 MANUAL_REVIEW 로 빠진다 — 무한 재시도 없음.
 */
@Service
public class PaymentCancelRetryService {

	private static final Logger log = LoggerFactory.getLogger(PaymentCancelRetryService.class);

	private final PaymentCancelRepository cancelRepository;
	private final PaymentCancelExecutor executor;
	private final TransactionTemplate tx;
	private final StaypointProperties properties;
	private final Clock clock;

	public PaymentCancelRetryService(PaymentCancelRepository cancelRepository, PaymentCancelExecutor executor,
			PlatformTransactionManager transactionManager, StaypointProperties properties, Clock clock) {
		this.cancelRepository = cancelRepository;
		this.executor = executor;
		this.tx = new TransactionTemplate(transactionManager);
		this.properties = properties;
		this.clock = clock;
	}

	/** 재시도할 때가 된 건을 처리한다. @return 시도한 건수 */
	public int retryDue() {
		List<Long> ids = tx.execute(status -> {
			Instant now = now();
			List<Long> due = cancelRepository.lockDueIds(now, properties.cancelRetry().batchSize());
			if (!due.isEmpty()) {
				cancelRepository.postpone(due, now.plus(PaymentCancel.PROCESSING_LEASE));
			}
			return due;
		});
		for (Long id : ids) {
			try {
				executor.attempt(id);
			} catch (RuntimeException e) {
				// 한 건의 예기치 않은 오류가 나머지를 막지 않게 한다. 미뤄 둔 시각이 지나면 다시 대상이 된다
				log.error("결제 취소 재시도 중 오류: cancelId={}", id, e);
			}
		}
		return ids.size();
	}

	/**
	 * 운영자 수동 재시도 (관리자 화면). MANUAL_REVIEW 건을 한 번 더 시도한다.
	 * PG 쪽 원인(장애 복구 등)을 확인한 뒤 누르는 버튼이라 백오프 없이 바로 시도한다.
	 */
	public PaymentCancel retryManually(Long cancelId) {
		boolean reopened = tx.execute(status -> {
			PaymentCancel cancel = cancelRepository.findByIdForUpdate(cancelId)
					.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "취소 요청을 찾을 수 없습니다.",
							Map.of("cancelId", cancelId)));
			return cancel.reopenForManualRetry(now());
		});
		if (reopened) {
			executor.attempt(cancelId);
		}
		return cancelRepository.findById(cancelId).orElseThrow();
	}

	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MICROS);
	}
}
