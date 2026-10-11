package com.staypoint.payment;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 결제 승인 요청 실패 1건의 기록 (설계 8.5 T23). PG 거절과 응답 없음·4xx 를 남긴다.
 * 해결 여부는 여기 두지 않는다 — payment.status 가 유일한 사실이고, 조회할 때 함께 읽는다.
 */
@Entity
@Table(name = "payment_approve_failure")
public class PaymentApproveFailure {

	private static final int MAX_MESSAGE_LENGTH = 500;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "payment_id", nullable = false, updatable = false)
	private Long paymentId;

	@Column(name = "error_code", nullable = false, updatable = false)
	private String errorCode;

	@Column(name = "http_status", updatable = false)
	private Integer httpStatus;

	@Column(name = "error_message", updatable = false)
	private String errorMessage;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	protected PaymentApproveFailure() {
	}

	/** PG 가 정상 응답(200)으로 승인을 거절함. reason 은 결제의 실패 사유와 같다. */
	public static PaymentApproveFailure declined(Long paymentId, String reason, Instant now) {
		return create(paymentId, PgClient.PgErrorCode.PG_DECLINED.name(), 200, reason, now);
	}

	/** 응답 없음·5xx·4xx — PgClient 예외를 분류한 결과. */
	public static PaymentApproveFailure of(Long paymentId, PgClient.PgError error, String message, Instant now) {
		return create(paymentId, error.code().name(), error.httpStatus(), message, now);
	}

	private static PaymentApproveFailure create(Long paymentId, String code, Integer httpStatus, String message,
			Instant now) {
		PaymentApproveFailure f = new PaymentApproveFailure();
		f.paymentId = paymentId;
		f.errorCode = code;
		f.httpStatus = httpStatus;
		f.errorMessage = message == null ? null : message.substring(0, Math.min(message.length(), MAX_MESSAGE_LENGTH));
		f.occurredAt = now;
		return f;
	}
}
