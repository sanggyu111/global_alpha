package com.staypoint.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.staypoint.payment.PaymentCancelRetryService;
import com.staypoint.support.ApiIntegrationTest;
import com.staypoint.support.HttpTestClient.Response;

/**
 * 자동 환불 실패 기록 (설계 8.4 T22): PG 환불 시도가 실패할 때마다 회차별로 남고, 재시도로 성공해도 지워지지 않는다.
 * 해결 여부는 payment_cancel.status 로 계산하고, 해결된 기록만 삭제할 수 있다.
 */
class PaymentCancelFailureLogTest extends ApiIntegrationTest {

	@Autowired
	PaymentCancelRetryService retryService;

	@Test
	void 장애_후_재시도로_환불되어도_실패_기록은_남고_해결됨으로_보인다() {
		long id = confirmedReservation();
		cancel(id, "?failTimes=2&failType=UNAVAILABLE");
		makeDue();
		retryService.retryDue(); // 2회차 실패
		makeDue();
		retryService.retryDue(); // 3회차 성공

		Response r = failures("");

		assertThat(r.status()).isEqualTo(200);
		assertThat(r.<List<Object>>json("$")).hasSize(2);
		assertThat(r.<Integer>json("$[0].attemptNo")).isEqualTo(2); // 최신순
		assertThat(r.<Integer>json("$[1].attemptNo")).isEqualTo(1);
		assertThat(r.<String>json("$[0].errorCode")).isEqualTo("PG_5XX");
		assertThat(r.<Integer>json("$[0].httpStatus")).isEqualTo(503);
		assertThat(r.<String>json("$[0].cancelStatus")).isEqualTo("SUCCEEDED");
		assertThat(r.<String>json("$[0].reservationNo")).isNotBlank();
	}

	@Test
	void PG_거절은_PG_REJECTED_와_HTTP_상태로_기록되고_미해결로_보인다() {
		long id = confirmedReservation();
		cancel(id, "?failTimes=1&failType=REJECTED");

		Response r = failures("");

		assertThat(r.<List<Object>>json("$")).hasSize(1);
		assertThat(r.<String>json("$[0].errorCode")).isEqualTo("PG_REJECTED");
		assertThat(r.<Integer>json("$[0].httpStatus")).isEqualTo(422);
		assertThat(r.<String>json("$[0].cancelStatus")).isEqualTo("MANUAL_REVIEW");
	}

	@Test
	void 수동_재시도_실패도_이어지는_회차로_기록된다() {
		long id = confirmedReservation();
		cancel(id, "?failTimes=2&failType=REJECTED"); // 1회차 거절 → MANUAL_REVIEW

		http.post("/api/admin/payment-cancels/" + cancelId() + "/retry", Map.of(), null); // 2회차 거절

		assertThat(jdbc.queryForList("SELECT attempt_no FROM payment_cancel_failure ORDER BY attempt_no", Integer.class))
				.containsExactly(1, 2);
	}

	@Test
	void 기간은_발생일_기준이고_양_끝을_포함한다() {
		long id = confirmedReservation();
		cancel(id, "?failTimes=1&failType=REJECTED");
		LocalDate today = LocalDate.now(clock);
		// 다른 날짜에 생긴 실패를 만든다 (서울 기준 3일 전 정오)
		jdbc.update("UPDATE payment_cancel_failure SET occurred_at = (?::date + time '12:00') AT TIME ZONE 'Asia/Seoul'",
				today.minusDays(3).toString());

		assertThat(failures("?from=" + today.minusDays(3) + "&to=" + today.minusDays(3)).<List<Object>>json("$")).hasSize(1);
		assertThat(failures("?from=" + today.minusDays(2) + "&to=" + today).<List<Object>>json("$")).isEmpty();
		assertThat(failures("?from=" + today + "&to=" + today.minusDays(1)).status()).isEqualTo(400);
	}

	@Test
	void 해결된_기록만_삭제할_수_있다() {
		long id = confirmedReservation();
		cancel(id, "?failTimes=1&failType=REJECTED"); // MANUAL_REVIEW = 미해결
		long failureId = jdbc.queryForObject("SELECT id FROM payment_cancel_failure", Long.class);

		Response unresolved = http.delete("/api/admin/payment-cancel-failures/" + failureId);
		assertThat(unresolved.status()).isEqualTo(409);
		assertThat(unresolved.<String>json("$.code")).isEqualTo("INVALID_STATE");

		http.post("/api/admin/payment-cancels/" + cancelId() + "/retry", Map.of(), null); // 성공 → 해결됨

		assertThat(http.delete("/api/admin/payment-cancel-failures/" + failureId).status()).isEqualTo(204);
		assertThat(fixtures.count("payment_cancel_failure")).isZero();
		assertThat(fixtures.count("payment_cancel")).isEqualTo(1); // 돈의 흐름 기록은 남는다
		assertThat(http.delete("/api/admin/payment-cancel-failures/" + failureId).<String>json("$.code"))
				.isEqualTo("NOT_FOUND");
	}

	private long confirmedReservation() {
		long id = reserve();
		assertThat(pay(id, "pay-" + id, "").<String>json("$.result")).isEqualTo("CONFIRMED");
		return id;
	}

	private void cancel(long reservationId, String query) {
		http.post("/api/reservations/" + reservationId + "/cancel" + query, Map.of("X-User-Id", USER), null);
	}

	private Response failures(String query) {
		return http.get("/api/admin/payment-cancel-failures" + query);
	}

	private long cancelId() {
		return jdbc.queryForObject("SELECT id FROM payment_cancel", Long.class);
	}

	private void makeDue() {
		jdbc.update("UPDATE payment_cancel SET next_retry_at = now() - interval '1 second'");
	}
}
