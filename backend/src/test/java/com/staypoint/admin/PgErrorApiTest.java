package com.staypoint.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.staypoint.payment.PaymentCancelRetryService;
import com.staypoint.payment.PaymentService;
import com.staypoint.support.ApiIntegrationTest;
import com.staypoint.support.HttpTestClient.Response;

/**
 * PG 오류 기록 (설계 8.4 T22 · 8.5 T23): 결제 승인 실패와 환불 실패가 시도마다 남고, 한 API 로 함께 조회된다.
 * 해결 여부는 원래 건의 현재 상태로 계산하고, 해결된 기록만 삭제할 수 있다.
 */
class PgErrorApiTest extends ApiIntegrationTest {

	@Autowired
	PaymentCancelRetryService retryService;

	@Autowired
	PaymentService paymentService;

	// ---------------------------------------------------------------- 결제 승인 실패 (T23)

	@Test
	void PG_거절은_FAILED_전이와_함께_한_번만_기록되고_중복_웹훅에도_늘지_않는다() {
		long id = reserve();
		assertThat(pay(id, "pay-1", "?failRate=1").<String>json("$.result")).isEqualTo("FAILED");
		String orderId = jdbc.queryForObject("SELECT pg_order_id FROM payment", String.class);

		paymentService.handleWebhook(orderId, "FAILED", null, null); // 같은 거절 통지가 늦게 또 옴

		Response r = pgErrors("");
		assertThat(r.<List<Object>>json("$")).hasSize(1);
		assertThat(r.<String>json("$[0].kind")).isEqualTo("APPROVE");
		assertThat(r.<String>json("$[0].errorCode")).isEqualTo("PG_DECLINED");
		assertThat(r.<Integer>json("$[0].httpStatus")).isEqualTo(200);
		assertThat(r.<String>json("$[0].errorMessage")).isEqualTo("PG 승인 거절");
		assertThat(r.<String>json("$[0].currentStatus")).isEqualTo("FAILED");
		assertThat(r.<Boolean>json("$[0].resolved")).isTrue();
		assertThat(r.<Object>json("$[0].attemptNo")).isNull();
	}

	@Test
	void 응답_없음은_PG_TIMEOUT_으로_기록되고_결과가_확정되기_전에는_지울_수_없다() {
		long id = reserve();
		// PG 는 승인을 기록한 뒤 2.5초 후 응답 → 우리 읽기 타임아웃(1.5초)이 먼저 끝난다
		assertThat(pay(id, "pay-1", "?delayMs=2500").<String>json("$.result")).isEqualTo("PROCESSING");

		Response r = pgErrors("?kind=APPROVE");
		assertThat(r.<String>json("$[0].errorCode")).isEqualTo("PG_TIMEOUT");
		assertThat(r.<Object>json("$[0].httpStatus")).isNull();
		assertThat(r.<String>json("$[0].currentStatus")).isEqualTo("READY");
		assertThat(r.<Boolean>json("$[0].resolved")).isFalse();
		long failureId = jdbc.queryForObject("SELECT id FROM payment_approve_failure", Long.class);
		assertThat(http.delete("/api/admin/pg-errors/APPROVE/" + failureId).status()).isEqualTo(409);

		jdbc.update("UPDATE payment SET created_at = now() - interval '2 minutes'"); // 1분 넘게 READY
		paymentService.reconcileStaleReady(); // PG 조회 → 승인돼 있었음

		assertThat(pgErrors("?kind=APPROVE").<String>json("$[0].currentStatus")).isEqualTo("APPROVED");
		assertThat(http.delete("/api/admin/pg-errors/APPROVE/" + failureId).status()).isEqualTo(204);
		assertThat(fixtures.count("payment")).isEqualTo(1); // 결제 기록은 남는다
	}

	// ---------------------------------------------------------------- 환불 실패 (T22)

	@Test
	void 장애_후_재시도로_환불되어도_실패_기록은_남고_해결됨으로_보인다() {
		long id = confirmedReservation();
		cancel(id, "?failTimes=2&failType=UNAVAILABLE");
		makeDue();
		retryService.retryDue(); // 2회차 실패
		makeDue();
		retryService.retryDue(); // 3회차 성공

		Response r = pgErrors("?kind=CANCEL");

		assertThat(r.status()).isEqualTo(200);
		assertThat(r.<List<Object>>json("$")).hasSize(2);
		assertThat(r.<Integer>json("$[0].attemptNo")).isEqualTo(2); // 최신순
		assertThat(r.<Integer>json("$[1].attemptNo")).isEqualTo(1);
		assertThat(r.<String>json("$[0].errorCode")).isEqualTo("PG_5XX");
		assertThat(r.<Integer>json("$[0].httpStatus")).isEqualTo(503);
		assertThat(r.<String>json("$[0].cancelReason")).isEqualTo("USER_CANCEL");
		assertThat(r.<String>json("$[0].currentStatus")).isEqualTo("SUCCEEDED");
		assertThat(r.<Boolean>json("$[0].resolved")).isTrue();
		assertThat(r.<String>json("$[0].reservationNo")).isNotBlank();
	}

	@Test
	void PG_거절_환불은_PG_REJECTED_와_HTTP_상태로_기록되고_미해결로_보인다() {
		long id = confirmedReservation();
		cancel(id, "?failTimes=1&failType=REJECTED");

		Response r = pgErrors("?kind=CANCEL");

		assertThat(r.<List<Object>>json("$")).hasSize(1);
		assertThat(r.<String>json("$[0].errorCode")).isEqualTo("PG_REJECTED");
		assertThat(r.<Integer>json("$[0].httpStatus")).isEqualTo(422);
		assertThat(r.<String>json("$[0].currentStatus")).isEqualTo("MANUAL_REVIEW");
		assertThat(r.<Boolean>json("$[0].resolved")).isFalse();
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
	void 해결된_환불_기록만_삭제할_수_있다() {
		long id = confirmedReservation();
		cancel(id, "?failTimes=1&failType=REJECTED"); // MANUAL_REVIEW = 미해결
		long failureId = jdbc.queryForObject("SELECT id FROM payment_cancel_failure", Long.class);

		Response unresolved = http.delete("/api/admin/pg-errors/CANCEL/" + failureId);
		assertThat(unresolved.status()).isEqualTo(409);
		assertThat(unresolved.<String>json("$.code")).isEqualTo("INVALID_STATE");

		http.post("/api/admin/payment-cancels/" + cancelId() + "/retry", Map.of(), null); // 성공 → 해결됨

		assertThat(http.delete("/api/admin/pg-errors/CANCEL/" + failureId).status()).isEqualTo(204);
		assertThat(fixtures.count("payment_cancel_failure")).isZero();
		assertThat(fixtures.count("payment_cancel")).isEqualTo(1); // 돈의 흐름 기록은 남는다
		assertThat(http.delete("/api/admin/pg-errors/CANCEL/" + failureId).<String>json("$.code"))
				.isEqualTo("NOT_FOUND");
	}

	// ---------------------------------------------------------------- 통합 조회 · 필터

	@Test
	void 승인_실패와_환불_실패를_한_목록으로_보고_종류_오류_코드_기간으로_거른다() {
		long declined = reserve();
		pay(declined, "pay-declined", "?failRate=1");                 // APPROVE / PG_DECLINED
		long refunded = reserve(roomTypeId, checkIn.plusDays(1));
		assertThat(pay(refunded, "pay-ok", "").<String>json("$.result")).isEqualTo("CONFIRMED");
		cancel(refunded, "?failTimes=1&failType=REJECTED");           // CANCEL / PG_REJECTED

		assertThat(pgErrors("").<List<String>>json("$[*].kind")).containsExactly("CANCEL", "APPROVE"); // 최신순
		assertThat(pgErrors("?kind=APPROVE").<List<String>>json("$[*].errorCode")).containsExactly("PG_DECLINED");
		assertThat(pgErrors("?code=PG_REJECTED").<List<String>>json("$[*].kind")).containsExactly("CANCEL");

		LocalDate today = LocalDate.now(clock);
		// 환불 실패를 서울 기준 3일 전 정오에 생긴 것으로 옮긴다
		jdbc.update("UPDATE payment_cancel_failure SET occurred_at = (?::date + time '12:00') AT TIME ZONE 'Asia/Seoul'",
				today.minusDays(3).toString());
		assertThat(pgErrors("?from=" + today.minusDays(3) + "&to=" + today.minusDays(3))
				.<List<String>>json("$[*].kind")).containsExactly("CANCEL");
		assertThat(pgErrors("?from=" + today.minusDays(2) + "&to=" + today)
				.<List<String>>json("$[*].kind")).containsExactly("APPROVE");
	}

	@Test
	void 잘못된_필터와_기간은_400() {
		LocalDate today = LocalDate.now(clock);
		assertThat(pgErrors("?kind=REFUND").status()).isEqualTo(400);
		assertThat(pgErrors("?code=BOOM").status()).isEqualTo(400);
		assertThat(pgErrors("?from=" + today + "&to=" + today.minusDays(1)).status()).isEqualTo(400);
		assertThat(http.delete("/api/admin/pg-errors/REFUND/1").status()).isEqualTo(400);
	}

	private long confirmedReservation() {
		long id = reserve();
		assertThat(pay(id, "pay-" + id, "").<String>json("$.result")).isEqualTo("CONFIRMED");
		return id;
	}

	private void cancel(long reservationId, String query) {
		http.post("/api/reservations/" + reservationId + "/cancel" + query, Map.of("X-User-Id", USER), null);
	}

	private Response pgErrors(String query) {
		return http.get("/api/admin/pg-errors" + query);
	}

	private long cancelId() {
		return jdbc.queryForObject("SELECT id FROM payment_cancel", Long.class);
	}

	private void makeDue() {
		jdbc.update("UPDATE payment_cancel SET next_retry_at = now() - interval '1 second'");
	}
}
