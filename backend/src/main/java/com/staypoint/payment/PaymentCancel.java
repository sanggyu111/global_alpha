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

	/**
	 * "지금 누가 처리 중" 표시 기간. 처리하는 쪽이 next_retry_at 을 이만큼 미뤄 두면 재시도 스케줄러가 집어 가지 않는다.
	 * 처리 중 서버가 죽어도 이 시간이 지나면 다시 대상이 된다 (cancel_key 덕분에 PG 에서 두 번 취소되지 않음).
	 */
	public static final Duration PROCESSING_LEASE = Duration.ofMinutes(1);

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
		return pending(payment, payment.getAmount(), Reason.COMPENSATION, "COMP-" + payment.getId(), now);
	}

	/** 사용자 취소에 따른 환불 (환불 정책에 따라 부분 금액일 수 있음). 예약당 사용자 취소는 한 번뿐이라 키를 결제 id 로 만든다. */
	public static PaymentCancel userCancel(Payment payment, BigDecimal refundAmount, Instant now) {
		return pending(payment, refundAmount, Reason.USER_CANCEL, "USER-" + payment.getId(), now);
	}

	private static PaymentCancel pending(Payment payment, BigDecimal amount, Reason reason, String cancelKey,
			Instant now) {
		PaymentCancel c = new PaymentCancel();
		c.paymentId = payment.getId();
		c.cancelAmount = amount;
		c.reason = reason;
		c.status = Status.PENDING;
		c.cancelKey = cancelKey;
		c.attemptCount = 0;
		// 만든 쪽이 커밋 직후 바로 1회 시도한다 → 그동안 스케줄러가 같은 건을 집지 않게 미뤄 둔다
		c.nextRetryAt = now.plus(PROCESSING_LEASE);
		c.createdAt = now;
		c.updatedAt = now;
		return c;
	}

	/**
	 * 운영자 수동 재시도: MANUAL_REVIEW 를 다시 PENDING 으로 돌려 한 번 더 시도하게 한다.
	 * 시도 횟수는 그대로라 이번에도 실패하면 바로 MANUAL_REVIEW 로 돌아간다 (자동 재시도가 다시 시작되지 않음).
	 *
	 * @return 시도할 수 있으면 true (이미 SUCCEEDED 면 false)
	 */
	public boolean reopenForManualRetry(Instant now) {
		if (status == Status.SUCCEEDED) {
			return false;
		}
		this.status = Status.PENDING;
		this.nextRetryAt = now.plus(PROCESSING_LEASE); // 운영자 요청이 바로 시도하므로 스케줄러와 겹치지 않게
		this.updatedAt = now;
		return true;
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
