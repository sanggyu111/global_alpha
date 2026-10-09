package com.staypoint.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.staypoint.support.ApiIntegrationTest;
import com.staypoint.support.HttpTestClient.Response;

/**
 * PG 취소가 계속 실패할 때 (S8, FR-RTY-1~3): 모의 PG 의 취소 실패율을 100%(항상 503)로 둔다.
 * 시간이 흐른 상황은 next_retry_at 을 과거로 옮겨 만든다.
 */
@TestPropertySource(properties = "mockpg.cancel.fail-rate=1.0")
class PaymentCancelRetryFailureTest extends ApiIntegrationTest {

	@Autowired
	PaymentCancelRetryService retryService;

	@Test
	void 사용자_취소_환불이_실패해도_취소는_성공하고_환불은_재시도_대상으로_남는다() {
		long id = confirmedReservation();

		Response r = http.post("/api/reservations/" + id + "/cancel", Map.of("X-User-Id", USER), null);

		assertThat(r.<String>json("$.status")).isEqualTo("CANCELED");
		assertThat(r.<String>json("$.refundStatus")).isEqualTo("PENDING");
		assertThat(jdbc.queryForObject("SELECT attempt_count || '/' || (last_error LIKE '%503%') FROM payment_cancel",
				String.class)).isEqualTo("1/true");
		assertThat(backoffMinutes()).isEqualTo(1);
	}

	@Test
	void 다섯_번_실패하면_1_2_4_8분_간격을_거쳐_운영자_확인_대상이_되고_더는_시도하지_않는다() {
		long cancelId = dueCancel();
		List<String> timeline = new ArrayList<>();

		for (int attempt = 1; attempt <= 5; attempt++) {
			makeDue(cancelId);
			assertThat(retryService.retryDue()).isEqualTo(1);
			timeline.add(state(cancelId) + (attempt < 5 ? "+" + backoffMinutes() + "m" : ""));
		}
		System.out.println("[취소 재시도] " + String.join(" → ", timeline));

		assertThat(timeline).containsExactly("PENDING/1+1m", "PENDING/2+2m", "PENDING/3+4m", "PENDING/4+8m",
				"MANUAL_REVIEW/5");
		makeDue(cancelId);
		assertThat(retryService.retryDue()).isZero(); // 6번째 시도 없음 (무한 재시도 금지)
		assertThat(state(cancelId)).isEqualTo("MANUAL_REVIEW/5");
		assertThat(jdbc.queryForObject("SELECT status FROM payment", String.class)).isEqualTo("APPROVED"); // 운영자 몫
	}

	@Test
	void 운영자_수동_재시도도_실패하면_다시_MANUAL_REVIEW_로_돌아가고_자동_재시도는_시작되지_않는다() {
		long cancelId = dueCancel();
		jdbc.update("UPDATE payment_cancel SET status = 'MANUAL_REVIEW', attempt_count = 5 WHERE id = ?", cancelId);

		Response r = http.post("/api/admin/payment-cancels/" + cancelId + "/retry", Map.of(), null);

		assertThat(r.<String>json("$.status")).isEqualTo("MANUAL_REVIEW");
		assertThat(r.<Integer>json("$.attemptCount")).isEqualTo(6);
		makeDue(cancelId);
		assertThat(retryService.retryDue()).isZero();
	}

	@Test
	void 스케줄러_3개가_동시에_돌아도_각_건은_한_번씩만_시도된다() throws Exception {
		List<Long> ids = List.of(dueCancel(), dueCancel(), dueCancel());

		ExecutorService executor = Executors.newFixedThreadPool(3);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> futures = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			futures.add(executor.submit(() -> {
				start.await();
				return retryService.retryDue();
			}));
		}
		start.countDown();
		int total = 0;
		for (Future<Integer> f : futures) {
			total += f.get(30, TimeUnit.SECONDS);
		}
		executor.shutdown();

		assertThat(total).isEqualTo(3); // SKIP LOCKED 로 나눠 가짐
		for (Long id : ids) {
			assertThat(state(id)).isEqualTo("PENDING/1"); // 두 번 시도되면 2 가 된다
		}
	}

	private long confirmedReservation() {
		long id = reserve();
		assertThat(pay(id, "pay-" + id, "").<String>json("$.result")).isEqualTo("CONFIRMED");
		return id;
	}

	/** 승인된 결제에 대해 지금 재시도할 차례인 보상 취소 요청 하나. */
	private long dueCancel() {
		long reservationId = confirmedReservation();
		long paymentId = jdbc.queryForObject("SELECT id FROM payment WHERE reservation_id = ?", Long.class, reservationId);
		return jdbc.queryForObject("INSERT INTO payment_cancel (payment_id, cancel_amount, reason, status, cancel_key, "
				+ "next_retry_at) VALUES (?, ?, 'COMPENSATION', 'PENDING', ?, now() - interval '1 second') RETURNING id",
				Long.class, paymentId, PRICE, "TEST-" + paymentId);
	}

	private void makeDue(long cancelId) {
		jdbc.update("UPDATE payment_cancel SET next_retry_at = now() - interval '1 second' WHERE id = ?", cancelId);
	}

	private String state(long cancelId) {
		return jdbc.queryForObject("SELECT status || '/' || attempt_count FROM payment_cancel WHERE id = ?",
				String.class, cancelId);
	}

	/** 마지막 실패 기록 시각 → 다음 시도 시각 간격(분). */
	private long backoffMinutes() {
		return jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM next_retry_at - updated_at)::bigint / 60 "
				+ "FROM payment_cancel ORDER BY updated_at DESC LIMIT 1", Long.class);
	}
}
