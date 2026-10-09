package com.staypoint.reservation.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.staypoint.reservation.Reservation;
import com.staypoint.reservation.ReservationStatus;

public record ReservationResponse(
		Long id,
		String reservationNo,
		ReservationStatus status,
		Long roomTypeId,
		LocalDate checkIn,
		LocalDate checkOut,
		int guestCount,
		String guestName,
		BigDecimal totalAmount,
		Instant holdExpiresAt,
		Instant createdAt
) {

	public static ReservationResponse from(Reservation r) {
		return new ReservationResponse(r.getId(), r.getReservationNo(), r.getStatus(), r.getRoomTypeId(),
				r.getCheckIn(), r.getCheckOut(), r.getGuestCount(), r.getGuestName(), r.getTotalAmount(),
				r.getHoldExpiresAt(), r.getCreatedAt());
	}
}
