package com.staypoint.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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

import com.staypoint.reservation.HoldExpiryService;
import com.staypoint.support.HttpTestClient.Response;

/**
 * 중복 승인 통지(S5)와 "승인됐는데 확정 실패" 보상 처리(S7, FR-PAY-5·7·8·9).
 */
class PaymentCompensationTest extends PaymentTestSupport {

	@Autowired
	PaymentProcessor processor;

	@Autowired
	PaymentService paymentService;

	@Autowired
	PgClient pgClient;

	@Autowired
	HoldExpiryService holdExpiryService;

	@Test
	void 같은_승인_통지가_동시에_3번_와도_결제_승인과_예약_확정은_한_번만_일어난다() throws Exception {
		long id = reserve();
		String orderId = readyPaymentApprovedAtPg(id);
		String tid = pgTid(orderId);

		ExecutorService executor = Executors.newFixedThreadPool(3);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Response>> futures = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			futures.add(executor.submit(() -> {
				start.await();
				return webhook("test-secret", orderId, "APPROVED", tid, PRICE);
			}));
		}
		start.countDown();
		for (Future<Response> f : futures) {
			assertThat(f.get(30, TimeUnit.SECONDS).status()).isEqualTo(200); // 중복 통지도 정상 응답 (PG 재전송 방지)
		}
		executor.shutdown();

		assertThat(reservationStatus(id)).isEqualTo("CONFIRMED");
		assertThat(count("SELECT COUNT(*) FROM payment WHERE status = 'APPROVED'")).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE to_status = 'CONFIRMED'")).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM payment_cancel")).isZero();
	}

	@Test
	void 비밀값이_틀리거나_없는_통지는_401_로_거부하고_아무것도_바꾸지_않는다() {
		long id = reserve();
		String orderId = readyPaymentApprovedAtPg(id);

		assertThat(webhook("wrong", orderId, "APPROVED", "T-X", PRICE).status()).isEqualTo(401);
		assertThat(http.post("/api/payments/webhook", Map.of(),
				"{\"orderId\":\"%s\",\"status\":\"APPROVED\"}".formatted(orderId)).status()).isEqualTo(401);
		assertThat(reservationStatus(id)).isEqualTo("PENDING");
	}

	@Test
	void PG_응답을_기다리는_사이_선점이_만료되면_승인을_보상_취소하고_예약은_확정하지_않는다() throws Exception {
		long id = reserve();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		Future<Response> payment = executor.submit(() -> pay(id, "pay-1", "?delayMs=1000"));

		// TX1 이 커밋되고 PG 응답을 기다리는 동안 만료 시각이 지나 스케줄러가 먼저 만료시킨다
		awaitReadyPayment();
		setHoldExpiresIn(id, -1);
		assertThat(holdExpiryService.expireNext()).isTrue();

		Response r = payment.get(10, TimeUnit.SECONDS);
		executor.shutdown();

		assertThat(r.<String>json("$.result")).isEqualTo("COMPENSATED");
		assertThat(reservationStatus(id)).isEqualTo("CANCELED");
		assertThat(fixtures.bookedCount(roomTypeId, checkIn)).isZero();
		// 보상 취소가 즉시 성공 → 우리 결제도, PG 의 결제도 취소됨 (돈이 남지 않음)
		assertThat(jdbc.queryForObject("SELECT status FROM payment", String.class)).isEqualTo("CANCELED");
		assertThat(jdbc.queryForObject("SELECT fail_reason FROM payment", String.class)).contains("RESERVATION_CANCELED");
		assertThat(jdbc.queryForObject("SELECT reason || '/' || status FROM payment_cancel", String.class))
				.isEqualTo("COMPENSATION/SUCCEEDED");
		assertThat(jdbc.queryForObject("SELECT status FROM mockpg_payment", String.class)).isEqualTo("CANCELED");
	}

	@Test
	void 만료_시각이_지났지만_스케줄러가_아직_안_돌았어도_승인을_확정하지_않고_보상_취소한다() {
		long id = reserve();
		String orderId = readyPaymentApprovedAtPg(id);
		setHoldExpiresIn(id, -1);

		paymentService.handleWebhook(orderId, "APPROVED", pgTid(orderId), BigDecimal.valueOf(PRICE));

		assertThat(reservationStatus(id)).isEqualTo("PENDING"); // 만료 처리는 스케줄러 몫
		assertThat(jdbc.queryForObject("SELECT fail_reason FROM payment", String.class)).contains("HOLD_EXPIRED");
		assertThat(jdbc.queryForObject("SELECT status FROM payment_cancel", String.class)).isEqualTo("SUCCEEDED");
	}

	@Test
	void 승인_금액이_예약_금액과_다르면_확정하지_않고_보상_취소한다() {
		long id = reserve();
		String orderId = readyPaymentApprovedAtPg(id);

		paymentService.handleWebhook(orderId, "APPROVED", pgTid(orderId), BigDecimal.valueOf(PRICE - 1));

		assertThat(reservationStatus(id)).isEqualTo("PENDING");
		assertThat(jdbc.queryForObject("SELECT fail_reason FROM payment", String.class)).contains("AMOUNT_MISMATCH");
		assertThat(jdbc.queryForObject("SELECT status FROM payment", String.class)).isEqualTo("CANCELED");
	}

	@Test
	void 보상_취소를_PG_가_거절하면_재시도하지_않고_운영자_확인_대상으로_남는다() {
		long id = reserve();
		String orderId = readyPaymentApprovedAtPg(id);
		setHoldExpiresIn(id, -1);

		// PG 에 없는 tid 로 승인 통지 → 확정 불가로 보상 취소 → PG 가 404 로 거절
		paymentService.handleWebhook(orderId, "APPROVED", "T-UNKNOWN", BigDecimal.valueOf(PRICE));

		assertThat(jdbc.queryForObject("SELECT status FROM payment_cancel", String.class)).isEqualTo("MANUAL_REVIEW");
		assertThat(count("SELECT attempt_count FROM payment_cancel")).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT last_error FROM payment_cancel", String.class)).contains("404");
		assertThat(jdbc.queryForObject("SELECT status FROM payment", String.class)).isEqualTo("APPROVED"); // 운영자가 처리
	}

	@Test
	void 거절_통지는_결제만_실패시키고_예약은_그대로다() {
		long id = reserve();
		String orderId = processor.prepare(id, USER, "pay-1").orderId();

		Response r = webhook("test-secret", orderId, "FAILED", null, PRICE);

		assertThat(r.status()).isEqualTo(200);
		assertThat(jdbc.queryForObject("SELECT status FROM payment", String.class)).isEqualTo("FAILED");
		assertThat(reservationStatus(id)).isEqualTo("PENDING");
	}

	/** TX1 만 실행해 READY 결제를 만들고, PG 에는 승인을 기록해 둔다 (응답·반영은 아직). */
	private String readyPaymentApprovedAtPg(long reservationId) {
		PaymentProcessor.Prepared prepared = processor.prepare(reservationId, USER, "pay-" + System.nanoTime());
		pgClient.approve(prepared.orderId(), prepared.amount(), 0.0, 0L);
		return prepared.orderId();
	}

	private String pgTid(String orderId) {
		return jdbc.queryForObject("SELECT tid FROM mockpg_payment WHERE order_id = ?", String.class, orderId);
	}

	private Response webhook(String secret, String orderId, String status, String tid, int amount) {
		String body = "{\"orderId\":\"%s\",\"status\":\"%s\",\"tid\":%s,\"amount\":%d}"
				.formatted(orderId, status, tid == null ? "null" : "\"" + tid + "\"", amount);
		return http.post("/api/payments/webhook", Map.of("X-Mock-PG-Secret", secret), body);
	}

	private void awaitReadyPayment() throws InterruptedException {
		for (int i = 0; i < 200; i++) {
			if (count("SELECT COUNT(*) FROM payment WHERE status = 'READY'") > 0) {
				return;
			}
			Thread.sleep(10);
		}
		throw new AssertionError("READY 결제가 생기지 않았습니다");
	}
}
