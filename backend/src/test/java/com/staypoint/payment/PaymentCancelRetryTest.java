package com.staypoint.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.staypoint.support.ApiIntegrationTest;
import com.staypoint.support.HttpTestClient.Response;

/**
 * 결제 취소 재시도 — PG 가 정상일 때 (설계 4.6). PG 가 계속 실패하는 경우는 PaymentCancelRetryFailureTest.
 */
class PaymentCancelRetryTest extends ApiIntegrationTest {

	@Autowired
	PaymentCancelRetryService retryService;

	@Test
	void 재시도_시각이_된_취소_요청은_스케줄러가_처리해_환불을_끝낸다() {
		long paymentId = approvedPayment();
		long cancelId = insertCancel(paymentId, "PENDING", 2, "now() - interval '1 second'");

		int attempted = retryService.retryDue();

		assertThat(attempted).isEqualTo(1);
		assertThat(cancelStatus(cancelId)).isEqualTo("SUCCEEDED/3");
		assertThat(jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, paymentId))
				.isEqualTo("CANCELED");
		assertThat(jdbc.queryForObject("SELECT status FROM mockpg_payment", String.class)).isEqualTo("CANCELED");
	}

	@Test
	void 재시도_시각이_안_된_건과_끝난_건은_건드리지_않는다() {
		long paymentId = approvedPayment();
		long later = insertCancel(paymentId, "PENDING", 1, "now() + interval '5 minutes'");

		assertThat(retryService.retryDue()).isZero();
		assertThat(cancelStatus(later)).isEqualTo("PENDING/1");
	}

	@Test
	void 새로_만든_취소_요청은_즉시_시도하는_동안_스케줄러가_집지_않도록_1분_뒤로_미뤄_둔다() {
		long id = confirmedReservation();
		jdbc.update("UPDATE mockpg_payment SET tid = 'T-GONE'"); // 즉시 시도가 실패하도록 (PG 404 → MANUAL_REVIEW)
		http.post("/api/reservations/" + id + "/cancel", Map.of("X-User-Id", USER), null);

		long secondsAhead = jdbc.queryForObject(
				"SELECT EXTRACT(EPOCH FROM next_retry_at - created_at)::bigint FROM payment_cancel", Long.class);
		assertThat(secondsAhead).isEqualTo(60);
	}

	@Test
	void 운영자가_수동_재시도하면_MANUAL_REVIEW_건을_한_번_더_시도한다() {
		long paymentId = approvedPayment();
		long cancelId = insertCancel(paymentId, "MANUAL_REVIEW", 5, "now()");

		Response r = http.post("/api/admin/payment-cancels/" + cancelId + "/retry", Map.of(), null);

		assertThat(r.status()).isEqualTo(200);
		assertThat(r.<String>json("$.status")).isEqualTo("SUCCEEDED");
		assertThat(r.<Integer>json("$.attemptCount")).isEqualTo(6);
		assertThat(jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, paymentId))
				.isEqualTo("CANCELED");
	}

	@Test
	void 이미_성공한_건의_수동_재시도는_아무것도_하지_않고_없는_건은_404() {
		long paymentId = approvedPayment();
		long cancelId = insertCancel(paymentId, "PENDING", 0, "now() - interval '1 second'");
		retryService.retryDue();

		Response r = http.post("/api/admin/payment-cancels/" + cancelId + "/retry", Map.of(), null);

		assertThat(r.<String>json("$.status")).isEqualTo("SUCCEEDED");
		assertThat(r.<Integer>json("$.attemptCount")).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM mockpg_cancel")).isEqualTo(1);
		assertThat(http.post("/api/admin/payment-cancels/9999/retry", Map.of(), null).status()).isEqualTo(404);
	}

	private long confirmedReservation() {
		long id = reserve();
		assertThat(pay(id, "pay-" + id, "").<String>json("$.result")).isEqualTo("CONFIRMED");
		return id;
	}

	private long approvedPayment() {
		long id = confirmedReservation();
		return jdbc.queryForObject("SELECT id FROM payment WHERE reservation_id = ?", Long.class, id);
	}

	private long insertCancel(long paymentId, String status, int attempts, String nextRetryAtSql) {
		return jdbc.queryForObject("INSERT INTO payment_cancel (payment_id, cancel_amount, reason, status, cancel_key, "
				+ "attempt_count, next_retry_at) VALUES (?, ?, 'COMPENSATION', ?, ?, ?, " + nextRetryAtSql
				+ ") RETURNING id", Long.class, paymentId, PRICE, status, "TEST-" + paymentId, attempts);
	}

	private String cancelStatus(long cancelId) {
		return jdbc.queryForObject("SELECT status || '/' || attempt_count FROM payment_cancel WHERE id = ?",
				String.class, cancelId);
	}
}
