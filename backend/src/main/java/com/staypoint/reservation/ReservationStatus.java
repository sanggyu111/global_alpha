package com.staypoint.reservation;

import java.util.Set;

/**
 * 예약 상태와 허용되는 전이 (기획 5.3, 설계 4.3).
 * 선점 만료는 별도 상태 없이 CANCELED + cancel_reason=HOLD_EXPIRED 로 표현한다.
 */
public enum ReservationStatus {
	PENDING,
	CONFIRMED,
	COMPLETED,
	CANCELED;

	public boolean canTransitionTo(ReservationStatus to) {
		return allowedNext().contains(to);
	}

	private Set<ReservationStatus> allowedNext() {
		return switch (this) {
			case PENDING -> Set.of(CONFIRMED, CANCELED);
			case CONFIRMED -> Set.of(CANCELED, COMPLETED);
			case COMPLETED, CANCELED -> Set.of();
		};
	}
}
