package com.staypoint.mockpg;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 모의 PG 전용 테이블 접근. 외부 시스템을 흉내 내는 부분이라 우리 도메인 모델(JPA 엔티티)과 섞지 않고
 * SQL 로 직접 다룬다. 멱등 처리(ON CONFLICT)와 잠금(FOR UPDATE)이 쿼리에 그대로 드러난다.
 */
@Repository
class MockPgRepository {

	private static final RowMapper<MockPgPayment> PAYMENT = (rs, i) -> new MockPgPayment(
			rs.getString("order_id"), rs.getString("tid"), rs.getBigDecimal("amount"),
			rs.getBigDecimal("canceled_amount"), rs.getString("status"));

	private final JdbcTemplate jdbc;

	MockPgRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 같은 orderId 가 이미 있으면 아무것도 하지 않는다. 동시에 같은 orderId 가 오면 뒤의 INSERT 는
	 * 앞 트랜잭션 커밋을 기다렸다가 충돌로 끝난다 → 승인은 정확히 한 번.
	 *
	 * @return 새로 기록했으면 true
	 */
	boolean insertPaymentIfAbsent(String orderId, String tid, BigDecimal amount, String status) {
		return jdbc.update("""
				INSERT INTO mockpg_payment (order_id, tid, amount, status) VALUES (?, ?, ?, ?)
				ON CONFLICT (order_id) DO NOTHING
				""", orderId, tid, amount, status) == 1;
	}

	Optional<MockPgPayment> findPayment(String orderId) {
		return jdbc.query("SELECT * FROM mockpg_payment WHERE order_id = ?", PAYMENT, orderId).stream().findFirst();
	}

	/** 같은 결제에 대한 취소는 이 잠금으로 줄을 세운다 (잔여 금액 검사와 차감 사이에 끼어들지 못하게). */
	Optional<MockPgPayment> lockPaymentByTid(String tid) {
		return jdbc.query("SELECT * FROM mockpg_payment WHERE tid = ? FOR UPDATE", PAYMENT, tid).stream().findFirst();
	}

	void updateCanceled(String tid, BigDecimal canceledAmount, String status) {
		jdbc.update("UPDATE mockpg_payment SET canceled_amount = ?, status = ? WHERE tid = ?", canceledAmount, status, tid);
	}

	Optional<MockPgCancel> findCancel(String cancelKey) {
		return jdbc.query("SELECT cancel_key, tid, amount FROM mockpg_cancel WHERE cancel_key = ?",
				(rs, i) -> new MockPgCancel(rs.getString("cancel_key"), rs.getString("tid"), rs.getBigDecimal("amount")),
				cancelKey).stream().findFirst();
	}

	void insertCancel(String cancelKey, String tid, BigDecimal amount) {
		jdbc.update("INSERT INTO mockpg_cancel (cancel_key, tid, amount) VALUES (?, ?, ?)", cancelKey, tid, amount);
	}

	record MockPgCancel(String cancelKey, String tid, BigDecimal amount) {
	}
}
