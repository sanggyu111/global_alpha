package com.staypoint.cancellation;

import org.springframework.stereotype.Service;

import com.staypoint.payment.PaymentCancelExecutor;
import com.staypoint.payment.PgClient;

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

	/** refundFault: 데모용 PG 환불 장애 주입 (T20, null 이면 없음). 이번 취소로 환불 요청이 생길 때만 PG 에 전달된다. */
	public CancellationResponse cancel(Long reservationId, String userId, PgClient.CancelFault refundFault) {
		CancellationProcessor.Cancelled cancelled = processor.cancel(reservationId, userId);
		if (cancelled.refundCancelId() != null) {
			cancelExecutor.attempt(cancelled.refundCancelId(), refundFault);
		}
		return processor.currentResult(reservationId);
	}
}
