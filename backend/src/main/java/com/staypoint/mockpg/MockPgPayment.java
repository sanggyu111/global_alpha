package com.staypoint.mockpg;

import java.math.BigDecimal;

/** 모의 PG 의 결제 기록 (mockpg_payment). tid 는 승인됐을 때만 있다. */
public record MockPgPayment(String orderId, String tid, BigDecimal amount, BigDecimal canceledAmount, String status) {

	public static final String APPROVED = "APPROVED";
	public static final String FAILED = "FAILED";
	public static final String CANCELED = "CANCELED";

	public BigDecimal remainingAmount() {
		return amount.subtract(canceledAmount);
	}
}
