package com.staypoint.payment;

import java.util.Set;

/** 결제 상태와 허용되는 전이 (AGENT.md 5장): READY → APPROVED → CANCELED, READY → FAILED. */
public enum PaymentStatus {
	READY,
	APPROVED,
	FAILED,
	CANCELED;

	public boolean canTransitionTo(PaymentStatus to) {
		return switch (this) {
			case READY -> Set.of(APPROVED, FAILED).contains(to);
			case APPROVED -> to == CANCELED;
			case FAILED, CANCELED -> false;
		};
	}
}
