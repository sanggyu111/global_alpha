package com.staypoint.payment;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * PG 환불 시도 실패 1회의 기록 (설계 8.4 T22). payment_cancel 의 last_error 는 덮어써지므로 회차별로 따로 남긴다.
 * 해결 여부는 여기 두지 않는다 — payment_cancel.status 가 유일한 사실이고, 조회할 때 함께 읽는다.
 */
@Entity
@Table(name = "payment_cancel_failure")
public class PaymentCancelFailure {

	private static final int MAX_MESSAGE_LENGTH = 500;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "payment_cancel_id", nullable = false, updatable = false)
	private Long paymentCancelId;

	@Column(name = "attempt_no", nullable = false, updatable = false)
	private int attemptNo;

	@Column(name = "error_code", nullable = false, updatable = false)
	private String errorCode;

	@Column(name = "http_status", updatable = false)
	private Integer httpStatus;

	@Column(name = "error_message", updatable = false)
	private String errorMessage;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	protected PaymentCancelFailure() {
	}

	/** attemptNo 는 실패를 반영한 뒤의 payment_cancel.attempt_count (= 이번이 몇 번째 시도였나). */
	public static PaymentCancelFailure of(Long paymentCancelId, int attemptNo, PgClient.PgError error, String message,
			Instant now) {
		PaymentCancelFailure f = new PaymentCancelFailure();
		f.paymentCancelId = paymentCancelId;
		f.attemptNo = attemptNo;
		f.errorCode = error.code().name();
		f.httpStatus = error.httpStatus();
		f.errorMessage = message == null ? null : message.substring(0, Math.min(message.length(), MAX_MESSAGE_LENGTH));
		f.occurredAt = now;
		return f;
	}
}
