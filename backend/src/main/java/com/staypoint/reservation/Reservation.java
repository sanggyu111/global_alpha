package com.staypoint.reservation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

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
 * 이번 Task(T04)는 생성(PENDING)만 다루고, 확정·취소·만료 전이는 T05·T07·T08 에서 추가한다.
 */
@Entity
@Table(name = "reservation")
public class Reservation {

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

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
