package com.staypoint.cancellation;

import org.springframework.stereotype.Service;

import com.staypoint.payment.PaymentCancelExecutor;

/**
 * 예약 취소 · 환불 (설계 4.5). 이 클래스에는 @Transactional 을 걸지 않는다:
 * 취소 트랜잭션 커밋 → PG 환불 1회 시도(트랜잭션 밖) 순서.
 * PG 환불이 실패해도 사용자 응답은 성공이다 — 환불할 금액은 payment_cancel 에 남아 재시도(T14) 대상이 된다.
 */
@Service
public class CancellationService {

	private final CancellationProcessor processor;
	private final PaymentCancelExecutor cancelExecutor;

	public CancellationService(CancellationProcessor processor, PaymentCancelExecutor cancelExecutor) {
		this.processor = processor;
		this.cancelExecutor = cancelExecutor;
	}

	public CancellationResponse cancel(Long reservationId, String userId) {
		CancellationProcessor.Cancelled cancelled = processor.cancel(reservationId, userId);
		if (cancelled.refundCancelId() != null) {
			cancelExecutor.attempt(cancelled.refundCancelId());
		}
		return processor.currentResult(reservationId);
	}
}
