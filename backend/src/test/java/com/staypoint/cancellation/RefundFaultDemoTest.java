package com.staypoint.cancellation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.staypoint.payment.PaymentCancelRetryService;
import com.staypoint.support.ApiIntegrationTest;
import com.staypoint.support.HttpTestClient.Response;

/**
 * 환불 실패 데모 (설계 5장 T20): 취소 요청의 failTimes·failType 으로 모의 PG 가 그 결제의 환불을 N번 실패시킨다.
 * 장애는 PG 가 기억하므로 파라미터가 없는 스케줄러 재시도에도 이어지고, 횟수를 다 쓰면 다시 성공한다.
 * 시간이 흐른 상황은 next_retry_at 을 과거로 옮겨 만든다.
 */
class RefundFaultDemoTest extends ApiIntegrationTest {

	@Autowired
	PaymentCancelRetryService retryService;

	@Test
	void PG_거절을_고르면_즉시_운영자_확인_대상이_되고_수동_재시도는_성공한다() {
		long id = confirmedReservation();

		Response r = cancel(id, "?failTimes=1&failType=REJECTED");

		assertThat(r.status()).isEqualTo(200);
		assertThat(r.<String>json("$.status")).isEqualTo("CANCELED");
		assertThat(r.<String>json("$.refundStatus")).isEqualTo("MANUAL_REVIEW");
		assertThat(refundState()).isEqualTo("MANUAL_REVIEW/1/true");
		assertThat(jdbc.queryForObject("SELECT status FROM payment", String.class)).isEqualTo("APPROVED");

		Response retried = http.post("/api/admin/payment-cancels/" + cancelId() + "/retry", Map.of(), null);

		assertThat(retried.<String>json("$.status")).isEqualTo("SUCCEEDED");
		assertThat(jdbc.queryForObject("SELECT status FROM payment", String.class)).isEqualTo("CANCELED");
		assertThat(jdbc.queryForObject("SELECT status FROM mockpg_payment", String.class)).isEqualTo("CANCELED");
	}

	@Test
	void 장애_5회를_고르면_자동_재시도를_모두_실패해_운영자_확인_대상이_되고_수동_재시도는_성공한다() {
		long id = confirmedReservation();

		Response r = cancel(id, "?failTimes=5&failType=UNAVAILABLE");

		assertThat(r.<String>json("$.refundStatus")).isEqualTo("PENDING"); // 첫 시도 실패 → 재시도 대기
		for (int attempt = 2; attempt <= 5; attempt++) {
			makeDue();
			assertThat(retryService.retryDue()).isEqualTo(1);
		}
		assertThat(jdbc.queryForObject("SELECT status || '/' || attempt_count FROM payment_cancel", String.class))
				.isEqualTo("MANUAL_REVIEW/5");
		makeDue();
		assertThat(retryService.retryDue()).isZero(); // 6번째 자동 시도 없음

		Response retried = http.post("/api/admin/payment-cancels/" + cancelId() + "/retry", Map.of(), null);

		assertThat(retried.<String>json("$.status")).isEqualTo("SUCCEEDED");
		assertThat(jdbc.queryForObject("SELECT status FROM payment", String.class)).isEqualTo("CANCELED");
	}

	@Test
	void 장애_1회를_고르면_다음_자동_재시도에서_환불된다() {
		long id = confirmedReservation();

		assertThat(cancel(id, "?failTimes=1").<String>json("$.refundStatus")).isEqualTo("PENDING");
		makeDue();
		assertThat(retryService.retryDue()).isEqualTo(1);

		assertThat(jdbc.queryForObject("SELECT status || '/' || attempt_count FROM payment_cancel", String.class))
				.isEqualTo("SUCCEEDED/2"); // 시도 횟수는 성공한 시도까지 센다 (실패 1 + 성공 1)
		assertThat(jdbc.queryForObject("SELECT status FROM payment", String.class)).isEqualTo("CANCELED");
	}

	@Test
	void 범위_밖_실패_횟수는_400_이고_예약은_취소되지_않는다() {
		long id = confirmedReservation();

		Response r = cancel(id, "?failTimes=11");

		assertThat(r.status()).isEqualTo(400);
		assertThat(r.<String>json("$.code")).isEqualTo("VALIDATION_FAILED");
		assertThat(reservationStatus(id)).isEqualTo("CONFIRMED");
		assertThat(cancel(id, "?failType=BOOM").status()).isEqualTo(400);
	}

	private long confirmedReservation() {
		long id = reserve();
		assertThat(pay(id, "pay-" + id, "").<String>json("$.result")).isEqualTo("CONFIRMED");
		return id;
	}

	private Response cancel(long reservationId, String query) {
		return http.post("/api/reservations/" + reservationId + "/cancel" + query, Map.of("X-User-Id", USER), null);
	}

	private long cancelId() {
		return jdbc.queryForObject("SELECT id FROM payment_cancel", Long.class);
	}

	private void makeDue() {
		jdbc.update("UPDATE payment_cancel SET next_retry_at = now() - interval '1 second'");
	}

	/** 상태/시도 횟수/마지막 오류가 PG 거절(422)인지. */
	private String refundState() {
		return jdbc.queryForObject("SELECT status || '/' || attempt_count || '/' || (last_error LIKE '%422%') "
				+ "FROM payment_cancel", String.class);
	}
}
