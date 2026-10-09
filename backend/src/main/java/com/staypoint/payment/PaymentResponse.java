package com.staypoint.payment;

import java.math.BigDecimal;

import com.staypoint.reservation.Reservation;
import com.staypoint.reservation.ReservationStatus;

/**
 * 결제 요청 응답. result 로 화면이 다음 행동을 정한다.
 * <ul>
 *   <li>CONFIRMED — 승인 · 예약 확정</li>
 *   <li>FAILED — PG 거절. 선점 만료 전이면 새 Idempotency-Key 로 다시 결제할 수 있다</li>
 *   <li>PROCESSING — PG 응답을 못 받음. 웹훅·상태 확정 스케줄러가 곧 확정하므로 예약 상태를 다시 조회</li>
 *   <li>COMPENSATED — 승인됐지만 예약을 확정할 수 없어(만료 등) 결제를 취소(환불) 처리 중</li>
 * </ul>
 */
public record PaymentResponse(
		Long paymentId,
		Long reservationId,
		Result result,
		PaymentStatus paymentStatus,
		ReservationStatus reservationStatus,
		BigDecimal amount,
		String failReason
) {

	public enum Result {
		CONFIRMED,
		FAILED,
		PROCESSING,
		COMPENSATED
	}

	static PaymentResponse of(Payment payment, Reservation reservation, boolean compensated) {
		Result result;
		if (compensated) {
			result = Result.COMPENSATED;
		} else {
			result = switch (payment.getStatus()) {
				case READY -> Result.PROCESSING;
				case FAILED -> Result.FAILED;
				case APPROVED, CANCELED -> Result.CONFIRMED;
			};
		}
		return new PaymentResponse(payment.getId(), reservation.getId(), result, payment.getStatus(),
				reservation.getStatus(), payment.getAmount(), payment.getFailReason());
	}
}
