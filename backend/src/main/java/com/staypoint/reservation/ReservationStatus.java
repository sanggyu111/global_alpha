package com.staypoint.reservation;

/** 예약 상태 (기획 5.3). 선점 만료는 별도 상태 없이 CANCELED + cancel_reason=HOLD_EXPIRED (설계 4.3). */
public enum ReservationStatus {
	PENDING,
	CONFIRMED,
	COMPLETED,
	CANCELED
}
