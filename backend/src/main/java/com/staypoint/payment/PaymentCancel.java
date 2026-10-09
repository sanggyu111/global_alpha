package com.staypoint.payment;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * PG 취소 요청 = 재시도 작업 큐(아웃박스) 한 건 (설계 4.6).
 * 취소가 필요하다는 사실을 먼저 DB 에 남기고(예약·결제 변경과 같은 트랜잭션), PG 호출은 커밋 후에 한다.
 * → PG 호출이 실패하거나 서버가 죽어도 "취소해야 할 돈" 이 기록에서 사라지지 않는다.
 */
@Entity
@Table(name = "payment_cancel")
public class PaymentCancel {

	public enum Reason {
		USER_CANCEL,
		COMPENSATION
	}

	public enum Status {
		PENDING,
		SUCCEEDED,
		MANUAL_REVIEW
	}

	private static final int MAX_ERROR_LENGTH = 500;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "payment_id", nullable = false, updatable = false)
	private Long paymentId;

	@Column(name = "cancel_amount", nullable = false, updatable = false, precision = 12, scale = 0)
	private BigDecimal cancelAmount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private Reason reason;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status;

	/** PG 에 함께 보내는 멱등 키. 재시도해도 같은 취소가 두 번 실행되지 않는다. */
	@Column(name = "cancel_key", nullable = false, updatable = false)
	private String cancelKey;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "last_error")
	private String lastError;

	@Column(name = "next_retry_at", nullable = false)
	private Instant nextRetryAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected PaymentCancel() {
	}

	/** 승인됐지만 예약을 확정할 수 없어 전액을 돌려주는 보상 취소. 결제당 하나뿐이라 키를 결제 id 로 만든다. */
	public static PaymentCancel compensation(Payment payment, Instant now) {
		PaymentCancel c = new PaymentCancel();
		c.paymentId = payment.getId();
		c.cancelAmount = payment.getAmount();
		c.reason = Reason.COMPENSATION;
		c.status = Status.PENDING;
		c.cancelKey = "COMP-" + payment.getId();
		c.attemptCount = 0;
		c.nextRetryAt = now;
		c.createdAt = now;
		c.updatedAt = now;
		return c;
	}

	public void succeed(Instant now) {
		this.attemptCount++;
		this.status = Status.SUCCEEDED;
		this.lastError = null;
		this.updatedAt = now;
	}

	/**
	 * 실패를 기록한다. 다시 해도 소용없는 실패(PG 가 4xx 로 거절)이거나 최대 시도 횟수에 닿으면
	 * MANUAL_REVIEW(운영자 확인 대상) 로 넘긴다 — 무한 재시도 금지.
	 * 그 외에는 1분 × 2^(시도−1) 뒤에 다시 시도한다 (1·2·4·8분).
	 */
	public void recordFailure(String error, boolean retryable, int maxAttempts, Instant now) {
		this.attemptCount++;
		this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH));
		this.updatedAt = now;
		if (!retryable || attemptCount >= maxAttempts) {
			this.status = Status.MANUAL_REVIEW;
			return;
		}
		this.nextRetryAt = now.plus(Duration.ofMinutes(1L << (attemptCount - 1)));
	}

	public Long getId() {
		return id;
	}

	public Long getPaymentId() {
		return paymentId;
	}

	public BigDecimal getCancelAmount() {
		return cancelAmount;
	}

	public Reason getReason() {
		return reason;
	}

	public Status getStatus() {
		return status;
	}

	public String getCancelKey() {
		return cancelKey;
	}

	public int getAttemptCount() {
		return attemptCount;
	}

	public String getLastError() {
		return lastError;
	}

	public Instant getNextRetryAt() {
		return nextRetryAt;
	}
}
