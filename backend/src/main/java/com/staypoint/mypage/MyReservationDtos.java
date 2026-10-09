package com.staypoint.mypage;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.staypoint.payment.PaymentStatus;
import com.staypoint.reservation.ReservationStatus;

/** 내 예약 조회 응답. */
public final class MyReservationDtos {

	private MyReservationDtos() {
	}

	public record ReservationSummary(Long id, String reservationNo, ReservationStatus status, String propertyName,
			String roomTypeName, LocalDate checkIn, LocalDate checkOut, BigDecimal totalAmount, Instant createdAt) {
	}

	public record PaymentSummary(Long paymentId, PaymentStatus status, BigDecimal amount, BigDecimal canceledAmount,
			String failReason) {
	}

	/** "지금 취소하면" 환불 예정 — 확정 예약에만 있다. */
	public record RefundEstimate(long daysBeforeCheckIn, int percent, BigDecimal amount) {
	}

	/**
	 * 예약 상세. 취소된 예약은 refundAmount(확정된 환불액)와 refundStatus(PG 환불 진행 상태)를,
	 * 확정 예약은 refundEstimate(지금 취소 시 환불 예정)를 보여준다.
	 */
	public record ReservationDetail(
			Long id, String reservationNo, ReservationStatus status,
			Long propertyId, String propertyName, Long roomTypeId, String roomTypeName,
			LocalDate checkIn, LocalDate checkOut, long nights, int guestCount, String guestName,
			BigDecimal totalAmount, Instant holdExpiresAt, Instant confirmedAt,
			Instant canceledAt, String cancelReason, BigDecimal refundAmount, String refundStatus,
			PaymentSummary payment, RefundEstimate refundEstimate) {
	}
}
