package com.staypoint.reservation;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 예약 상태 전이 이력 (from → to, 사유, 누가). 상태가 바뀌는 트랜잭션 안에서 함께 기록한다. */
@Entity
@Table(name = "reservation_history")
public class ReservationHistory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "reservation_id", nullable = false, updatable = false)
	private Long reservationId;

	@Enumerated(EnumType.STRING)
	@Column(name = "from_status", updatable = false)
	private ReservationStatus fromStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "to_status", nullable = false, updatable = false)
	private ReservationStatus toStatus;

	@Column(updatable = false)
	private String reason;

	@Column(nullable = false, updatable = false)
	private String actor;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected ReservationHistory() {
	}

	public static ReservationHistory of(Long reservationId, ReservationStatus from, ReservationStatus to,
			String reason, String actor, Instant now) {
		ReservationHistory h = new ReservationHistory();
		h.reservationId = reservationId;
		h.fromStatus = from;
		h.toStatus = to;
		h.reason = reason;
		h.actor = actor;
		h.createdAt = now;
		return h;
	}

	public Long getReservationId() {
		return reservationId;
	}

	public ReservationStatus getFromStatus() {
		return fromStatus;
	}

	public ReservationStatus getToStatus() {
		return toStatus;
	}

	public String getReason() {
		return reason;
	}

	public String getActor() {
		return actor;
	}
}
