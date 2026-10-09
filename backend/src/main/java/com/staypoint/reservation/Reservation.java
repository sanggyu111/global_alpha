package com.staypoint.reservation;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import com.staypoint.common.error.BusinessException;
import com.staypoint.common.error.ErrorCode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 예약. 상태는 도메인 메서드로만 바꾼다 (AGENT.md 5장).
 * 상태를 바꾸는 호출자는 먼저 예약 행을 잠가야 한다 (설계 4.3).
 */
@Entity
@Table(name = "reservation")
public class Reservation {

	public static final String CANCEL_REASON_HOLD_EXPIRED = "HOLD_EXPIRED";
	public static final String CANCEL_REASON_USER = "USER_CANCEL";

	/** 결제를 시작하려면 선점이 최소 이만큼 남아 있어야 한다 (설계 4.7). */
	public static final Duration PAYMENT_MIN_REMAINING = Duration.ofSeconds(30);

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "reservation_no", nullable = false, updatable = false)
	private String reservationNo;

	@Column(name = "user_id", nullable = false, updatable = false)
	private String userId;

	@Column(name = "room_type_id", nullable = false, updatable = false)
	private Long roomTypeId;

	@Column(name = "check_in", nullable = false, updatable = false)
	private LocalDate checkIn;

	@Column(name = "check_out", nullable = false, updatable = false)
	private LocalDate checkOut;

	@Column(name = "guest_count", nullable = false)
	private int guestCount;

	@Column(name = "guest_name", nullable = false)
	private String guestName;

	@Column(name = "guest_phone", nullable = false)
	private String guestPhone;

	/** 생성 시점 요금으로 확정한 총액. 이후 요금이 바뀌어도 변하지 않는다 (기획 5.5). */
	@Column(name = "total_amount", nullable = false, updatable = false, precision = 12, scale = 0)
	private BigDecimal totalAmount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ReservationStatus status;

	@Column(name = "hold_expires_at", nullable = false)
	private Instant holdExpiresAt;

	@Column(name = "confirmed_at")
	private Instant confirmedAt;

	@Column(name = "canceled_at")
	private Instant canceledAt;

	@Column(name = "cancel_reason")
	private String cancelReason;

	@Column(name = "refund_amount", precision = 12, scale = 0)
	private BigDecimal refundAmount;

	@Column(name = "idempotency_key", nullable = false, updatable = false)
	private String idempotencyKey;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Reservation() {
	}

	/** 재고를 선점한 PENDING 예약을 만든다. holdExpiresAt 이 지나도록 결제되지 않으면 만료 대상이 된다. */
	public static Reservation hold(String reservationNo, String userId, String idempotencyKey,
			Long roomTypeId, LocalDate checkIn, LocalDate checkOut, int guestCount,
			String guestName, String guestPhone, BigDecimal totalAmount,
			Instant now, Instant holdExpiresAt) {
		Reservation r = new Reservation();
		r.reservationNo = reservationNo;
		r.userId = userId;
		r.idempotencyKey = idempotencyKey;
		r.roomTypeId = roomTypeId;
		r.checkIn = checkIn;
		r.checkOut = checkOut;
		r.guestCount = guestCount;
		r.guestName = guestName;
		r.guestPhone = guestPhone;
		r.totalAmount = totalAmount;
		r.status = ReservationStatus.PENDING;
		r.holdExpiresAt = holdExpiresAt;
		r.createdAt = now;
		r.updatedAt = now;
		return r;
	}

	/**
	 * 선점 만료: PENDING → CANCELED(HOLD_EXPIRED). 재고 복원은 호출한 쪽이 같은 트랜잭션에서 한다.
	 *
	 * @return 전이 전 상태 (이력 기록용)
	 */
	public ReservationStatus expire(Instant now) {
		if (holdExpiresAt.isAfter(now)) {
			throw new BusinessException(ErrorCode.INVALID_STATE, "아직 선점 시간이 남아 있습니다.",
					Map.of("holdExpiresAt", holdExpiresAt.toString()));
		}
		ReservationStatus from = transitionTo(ReservationStatus.CANCELED, now);
		this.canceledAt = now;
		this.cancelReason = CANCEL_REASON_HOLD_EXPIRED;
		return from;
	}

	/**
	 * 결제를 시작해도 되는지 검사한다 (설계 4.7). 만료 스케줄러가 아직 돌지 않았더라도
	 * 만료 시각이 지났으면 거부한다. 남은 시간이 너무 짧아도 거부해 "결제 직후 만료" 경합을 줄인다.
	 * 정확성은 결제 반영 시 예약 행 잠금 + 상태 재확인이 보장하고, 이 검사는 사용자 경험 개선용이다.
	 */
	public void assertPayable(Instant now) {
		if (status != ReservationStatus.PENDING) {
			throw new BusinessException(ErrorCode.INVALID_STATE, "결제할 수 없는 예약 상태입니다: " + status,
					Map.of("status", status.name()));
		}
		if (!now.plus(PAYMENT_MIN_REMAINING).isBefore(holdExpiresAt)) {
			throw new BusinessException(ErrorCode.HOLD_EXPIRED, ErrorCode.HOLD_EXPIRED.defaultMessage(),
					Map.of("holdExpiresAt", holdExpiresAt.toString()));
		}
	}

	/**
	 * 사용자 취소: PENDING · CONFIRMED → CANCELED. 환불 금액은 호출한 쪽이 환불 정책으로 계산해 넘긴다.
	 * 재고 복원·PG 취소 요청은 호출한 쪽이 같은 트랜잭션에서 한다.
	 *
	 * @return 전이 전 상태 (이력 기록용)
	 */
	public ReservationStatus cancelByUser(BigDecimal refundAmount, Instant now) {
		ReservationStatus from = transitionTo(ReservationStatus.CANCELED, now);
		this.canceledAt = now;
		this.cancelReason = CANCEL_REASON_USER;
		this.refundAmount = refundAmount;
		return from;
	}

	/** 결제 승인을 반영해 확정할 수 있는가: PENDING 이고 선점 만료 전 (스케줄러가 아직 안 돌았어도 시각 기준). */
	public boolean isConfirmable(Instant now) {
		return status == ReservationStatus.PENDING && now.isBefore(holdExpiresAt);
	}

	/**
	 * 결제 승인으로 확정: PENDING → CONFIRMED. 만료 시각이 지났으면 확정하지 않는다 (FR-PAY-8).
	 *
	 * @return 전이 전 상태 (이력 기록용)
	 */
	public ReservationStatus confirm(Instant now) {
		if (status == ReservationStatus.PENDING && !now.isBefore(holdExpiresAt)) {
			throw new BusinessException(ErrorCode.HOLD_EXPIRED, ErrorCode.HOLD_EXPIRED.defaultMessage(),
					Map.of("holdExpiresAt", holdExpiresAt.toString()));
		}
		ReservationStatus from = transitionTo(ReservationStatus.CONFIRMED, now);
		this.confirmedAt = now;
		return from;
	}

	/** 모든 상태 변경은 여기를 거친다. 허용되지 않은 전이는 예외. */
	private ReservationStatus transitionTo(ReservationStatus to, Instant now) {
		if (!status.canTransitionTo(to)) {
			throw new BusinessException(ErrorCode.INVALID_STATE, status + " → " + to + " 전이는 허용되지 않습니다.",
					Map.of("from", status.name(), "to", to.name()));
		}
		ReservationStatus from = this.status;
		this.status = to;
		this.updatedAt = now;
		return from;
	}

	public Long getId() {
		return id;
	}

	public String getReservationNo() {
		return reservationNo;
	}

	public String getUserId() {
		return userId;
	}

	public Long getRoomTypeId() {
		return roomTypeId;
	}

	public LocalDate getCheckIn() {
		return checkIn;
	}

	public LocalDate getCheckOut() {
		return checkOut;
	}

	public int getGuestCount() {
		return guestCount;
	}

	public String getGuestName() {
		return guestName;
	}

	public String getGuestPhone() {
		return guestPhone;
	}

	public BigDecimal getTotalAmount() {
		return totalAmount;
	}

	public ReservationStatus getStatus() {
		return status;
	}

	public Instant getHoldExpiresAt() {
		return holdExpiresAt;
	}

	public Instant getConfirmedAt() {
		return confirmedAt;
	}

	public Instant getCanceledAt() {
		return canceledAt;
	}

	public String getCancelReason() {
		return cancelReason;
	}

	public BigDecimal getRefundAmount() {
		return refundAmount;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
