package com.staypoint.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 관리자 API 요청·응답. 날짜 범위 from ~ to 는 양 끝 포함. */
public final class AdminDtos {

	private AdminDtos() {
	}

	public record Page<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

		static <T> Page<T> of(List<T> content, int page, int size, long total) {
			return new Page<>(content, page, size, total, (int) ((total + size - 1) / size));
		}
	}

	public record AdminReservation(Long id, String reservationNo, String userId, String status,
			Long propertyId, String propertyName, Long roomTypeId, String roomTypeName,
			LocalDate checkIn, LocalDate checkOut, int guestCount, String guestName, BigDecimal totalAmount,
			String cancelReason, Instant createdAt) {
	}

	/** 재고·요금이 등록되지 않은 날짜는 값이 null. available = total − booked. */
	public record CalendarDay(LocalDate date, Integer totalCount, Integer bookedCount, Integer available,
			BigDecimal price) {
	}

	public record InventoryRequest(@NotNull LocalDate from, @NotNull LocalDate to, @NotNull @Min(0) Integer totalCount) {
	}

	public record RateRequest(@NotNull LocalDate from, @NotNull LocalDate to, @NotNull @DecimalMin("0") BigDecimal price) {
	}

	public record PaymentCancelIssue(Long id, String reason, String status, BigDecimal cancelAmount, int attemptCount,
			String lastError, Instant nextRetryAt, Instant createdAt, Instant updatedAt,
			Long paymentId, String pgOrderId, String pgTid, Long reservationId, String reservationNo) {
	}

	/**
	 * PG 오류 기록 1건 (T23) — 결제 승인 실패(kind=APPROVE) 또는 환불 실패(kind=CANCEL).
	 * currentStatus 는 APPROVE 면 payment.status, CANCEL 이면 payment_cancel.status. resolved = 결과 확정(삭제 가능).
	 * attemptNo · cancelReason 은 환불에만 있다.
	 */
	public record PgErrorRecord(String kind, Long id, Instant occurredAt, Long reservationId, String reservationNo,
			Long paymentId, Integer attemptNo, String cancelReason, String errorCode, Integer httpStatus,
			String errorMessage, String currentStatus, boolean resolved) {
	}

	public record PaymentCancelRetryResult(Long id, String status, int attemptCount, String lastError) {
	}

	/** expectedBookedCount = 그 날짜를 쓰는 활성 예약(PENDING · CONFIRMED · COMPLETED) 수 = 정답. */
	public record InventoryMismatch(Long roomTypeId, String propertyName, String roomTypeName, LocalDate stayDate,
			int totalCount, int bookedCount, int expectedBookedCount) {
	}

	public record RecountResult(Long roomTypeId, LocalDate stayDate, int before, int after) {
	}
}
