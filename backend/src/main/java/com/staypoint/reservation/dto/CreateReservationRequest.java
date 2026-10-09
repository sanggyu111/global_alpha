package com.staypoint.reservation.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 예약 생성 요청. 형식 검증은 여기서, 날짜·정원처럼 현재 시각이나 DB 값이 필요한 검증은
 * ReservationService 에서 한다 (FR-RES-5).
 */
public record CreateReservationRequest(
		@NotNull Long roomTypeId,
		@NotNull LocalDate checkIn,
		@NotNull LocalDate checkOut,
		@NotNull @Min(1) Integer guestCount,
		@NotBlank @Size(max = 50) String guestName,
		@NotBlank @Size(max = 30) String guestPhone
) {
}
