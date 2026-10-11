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
		@NotNull(message = "객실 타입을 선택해 주세요.") Long roomTypeId,
		@NotNull(message = "체크인 날짜를 입력해 주세요.") LocalDate checkIn,
		@NotNull(message = "체크아웃 날짜를 입력해 주세요.") LocalDate checkOut,
		@NotNull(message = "인원을 입력해 주세요.") @Min(value = 1, message = "인원은 1명 이상이어야 합니다.") Integer guestCount,
		@NotBlank(message = "예약자 이름을 입력해 주세요.") @Size(max = 50, message = "예약자 이름은 50자 이하여야 합니다.") String guestName,
		@NotBlank(message = "연락처를 입력해 주세요.") @Size(max = 30, message = "연락처는 30자 이하여야 합니다.") String guestPhone
) {
}
