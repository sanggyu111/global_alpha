package com.staypoint.cancellation;

import java.math.BigDecimal;
import java.time.Instant;

import com.staypoint.payment.PaymentCancel;
import com.staypoint.reservation.Reservation;
import com.staypoint.reservation.ReservationStatus;

/**
 * 취소 응답. 이미 취소된 예약을 다시 취소해도 처음 취소 때 저장한 값(취소 시각·사유·환불액)을 돌려준다.
 * refundStatus: NONE(환불할 돈 없음) / PENDING(PG 환불 진행·재시도 중) / SUCCEEDED / MANUAL_REVIEW(운영자 확인 중)
 */
public record CancellationResponse(
		Long reservationId,
		ReservationStatus status,
		String cancelReason,
		Instant canceledAt,
		BigDecimal totalAmount,
		BigDecimal refundAmount,
		String refundStatus
) {

	static CancellationResponse of(Reservation r, PaymentCancel refund) {
		BigDecimal refundAmount = r.getRefundAmount() == null ? BigDecimal.ZERO : r.getRefundAmount();
		String refundStatus = refund == null ? "NONE" : refund.getStatus().name();
		return new CancellationResponse(r.getId(), r.getStatus(), r.getCancelReason(), r.getCanceledAt(),
				r.getTotalAmount(), refundAmount, refundStatus);
	}
}
