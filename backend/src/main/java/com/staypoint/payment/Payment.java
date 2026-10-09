package com.staypoint.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.staypoint.common.error.BusinessException;
import com.staypoint.common.error.ErrorCode;

/**
 * 결제 1회 시도. 예약 1건에 여러 번 생길 수 있지만(실패 후 재결제), 진행 중이거나 승인된 결제는
 * 최대 1건이다 (부분 UNIQUE 인덱스 uq_payment_active_per_reservation).
 */
@Entity
@Table(name = "payment")
public class Payment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "reservation_id", nullable = false, updatable = false)
	private Long reservationId;

	/** PG 에 보내는 주문번호. 같은 orderId 로 다시 승인 요청하면 PG 는 처음 결과를 돌려준다. */
	@Column(name = "pg_order_id", nullable = false, updatable = false)
	private String pgOrderId;

	@Column(name = "pg_tid")
	private String pgTid;

	@Column(nullable = false, updatable = false, precision = 12, scale = 0)
	private BigDecimal amount;

	@Column(name = "canceled_amount", nullable = false, precision = 12, scale = 0)
	private BigDecimal canceledAmount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PaymentStatus status;

	@Column(name = "idempotency_key", nullable = false, updatable = false)
	private String idempotencyKey;

	/** 승인 거절 사유, 또는 승인됐지만 예약을 확정하지 못해 보상 취소한 사유. */
	@Column(name = "fail_reason")
	private String failReason;

	@Column(name = "approved_at")
	private Instant approvedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Payment() {
	}

	public static Payment ready(Long reservationId, String pgOrderId, BigDecimal amount, String idempotencyKey,
			Instant now) {
		Payment p = new Payment();
		p.reservationId = reservationId;
		p.pgOrderId = pgOrderId;
		p.amount = amount;
		p.canceledAmount = BigDecimal.ZERO;
		p.status = PaymentStatus.READY;
		p.idempotencyKey = idempotencyKey;
		p.createdAt = now;
		p.updatedAt = now;
		return p;
	}

	public void approve(String tid, Instant now) {
		transitionTo(PaymentStatus.APPROVED, now);
		this.pgTid = tid;
		this.approvedAt = now;
	}

	public void fail(String reason, Instant now) {
		transitionTo(PaymentStatus.FAILED, now);
		this.failReason = reason;
	}

	/** 승인은 됐지만 예약 확정을 못 해 보상 취소 대상이 된 이유를 남긴다 (운영자가 볼 흔적). */
	public void markCompensation(String reason, Instant now) {
		this.failReason = "확정 실패로 보상 취소: " + reason;
		this.updatedAt = now;
	}

	/** PG 취소 성공을 반영한다. 전액이 취소되면 CANCELED, 부분 취소면 APPROVED 유지. */
	public void applyCancel(BigDecimal cancelAmount, Instant now) {
		if (status != PaymentStatus.APPROVED) {
			throw new BusinessException(ErrorCode.INVALID_STATE, "승인된 결제만 취소할 수 있습니다: " + status,
					Map.of("status", status.name()));
		}
		this.canceledAmount = canceledAmount.add(cancelAmount);
		this.updatedAt = now;
		if (canceledAmount.compareTo(amount) == 0) {
			transitionTo(PaymentStatus.CANCELED, now);
		}
	}

	private void transitionTo(PaymentStatus to, Instant now) {
		if (!status.canTransitionTo(to)) {
			throw new BusinessException(ErrorCode.INVALID_STATE, "결제 " + status + " → " + to + " 전이는 허용되지 않습니다.",
					Map.of("from", status.name(), "to", to.name()));
		}
		this.status = to;
		this.updatedAt = now;
	}

	public Long getId() {
		return id;
	}

	public Long getReservationId() {
		return reservationId;
	}

	public String getPgOrderId() {
		return pgOrderId;
	}

	public String getPgTid() {
		return pgTid;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public BigDecimal getCanceledAmount() {
		return canceledAmount;
	}

	public PaymentStatus getStatus() {
		return status;
	}

	public String getFailReason() {
		return failReason;
	}
}
