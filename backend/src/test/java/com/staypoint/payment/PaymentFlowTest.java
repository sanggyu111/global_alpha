package com.staypoint.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.staypoint.reservation.HoldExpiryService;
import com.staypoint.support.ApiIntegrationTest;
import com.staypoint.support.HttpTestClient.Response;

/**
 * 결제 요청 → 모의 PG(HTTP) → 확정 (설계 4.4, FR-PAY-4·6, S4 · S6, 타임아웃).
 */
class PaymentFlowTest extends ApiIntegrationTest {

	@Autowired
	HoldExpiryService holdExpiryService;

	@Autowired
	PaymentService paymentService;

	@Test
	void 승인되면_예약이_CONFIRMED_가_되고_이력이_남는다() {
		long id = reserve();

		Response r = pay(id, "pay-1", "");

		assertThat(r.status()).isEqualTo(200);
		assertThat(r.<String>json("$.result")).isEqualTo("CONFIRMED");
		assertThat(r.<String>json("$.paymentStatus")).isEqualTo("APPROVED");
		assertThat(r.<Integer>json("$.amount")).isEqualTo(PRICE);
		assertThat(reservationStatus(id)).isEqualTo("CONFIRMED");
		assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE reservation_id = ? AND to_status = 'CONFIRMED' "
				+ "AND actor = 'system:payment'", id)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM mockpg_payment")).isEqualTo(1);
	}

	@Test
	void PG_가_거절하면_FAILED_이고_예약은_PENDING_이라_새_키로_다시_결제할_수_있다() {
		long id = reserve();

		Response failed = pay(id, "pay-1", "?failRate=1");
		assertThat(failed.<String>json("$.result")).isEqualTo("FAILED");
		assertThat(reservationStatus(id)).isEqualTo("PENDING");

		Response retried = pay(id, "pay-2", "?failRate=0");
		assertThat(retried.<String>json("$.result")).isEqualTo("CONFIRMED");
		assertThat(count("SELECT COUNT(*) FROM payment WHERE reservation_id = ?", id)).isEqualTo(2);
	}

	@Test
	void 같은_결제_키로_다시_요청하면_PG_를_다시_승인하지_않고_같은_결과를_돌려준다() {
		long id = reserve();
		Response first = pay(id, "pay-1", "");

		Response second = pay(id, "pay-1", "?failRate=1");

		assertThat(second.body()).isEqualTo(first.body());
		assertThat(count("SELECT COUNT(*) FROM payment")).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM mockpg_payment")).isEqualTo(1);
	}

	@Test
	void 다른_키로_결제_버튼을_동시에_두_번_눌러도_승인은_한_건이다() throws Exception {
		long id = reserve();

		// 첫 결제가 PG 응답을 기다리는 동안(800ms) 두 번째가 도착 → 예약 행 잠금 뒤 진행 중 결제를 보고 거부
		List<Response> responses = concurrently(2, i -> pay(id, "pay-" + i, "?delayMs=800"));

		assertThat(responses).extracting(Response::status).containsExactlyInAnyOrder(200, 409);
		Response rejected = responses.stream().filter(r -> r.status() == 409).findFirst().orElseThrow();
		assertThat(rejected.<String>json("$.code")).isEqualTo("PAYMENT_IN_PROGRESS");
		assertThat(count("SELECT COUNT(*) FROM payment WHERE status = 'APPROVED'")).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM mockpg_payment")).isEqualTo(1);
		assertThat(reservationStatus(id)).isEqualTo("CONFIRMED");
	}

	@Test
	void 같은_키로_동시에_두_번_요청해도_결제는_하나이고_둘_다_같은_결과를_받는다() throws Exception {
		long id = reserve();

		List<Response> responses = concurrently(2, i -> pay(id, "pay-same", "?delayMs=300"));

		assertThat(responses).extracting(Response::status).containsOnly(200);
		assertThat(responses).extracting(r -> r.<String>json("$.result")).containsOnly("CONFIRMED");
		assertThat(responses).extracting(r -> r.<Integer>json("$.paymentId")).containsOnly(
				responses.get(0).<Integer>json("$.paymentId"));
		assertThat(count("SELECT COUNT(*) FROM payment")).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE to_status = 'CONFIRMED'")).isEqualTo(1);
	}

	@Test
	void 선점이_만료된_예약은_결제를_거부하고_만료_처리로_재고가_복원된다() {
		long id = reserve();
		setHoldExpiresIn(id, -1); // 10분 경과 — 스케줄러는 아직 안 돈 상태

		Response r = pay(id, "pay-1", "");

		assertThat(r.status()).isEqualTo(409);
		assertThat(r.<String>json("$.code")).isEqualTo("HOLD_EXPIRED");
		assertThat(count("SELECT COUNT(*) FROM payment")).isZero();
		assertThat(count("SELECT COUNT(*) FROM mockpg_payment")).isZero(); // PG 호출 자체가 없다

		holdExpiryService.expireNext();
		assertThat(reservationStatus(id)).isEqualTo("CANCELED");
		assertThat(fixtures.bookedCount(roomTypeId, checkIn)).isZero();
	}

	@Test
	void 선점이_30초_이하로_남으면_결제를_거부한다() {
		long id = reserve();
		setHoldExpiresIn(id, 20);

		Response r = pay(id, "pay-1", "");

		assertThat(r.<String>json("$.code")).isEqualTo("HOLD_EXPIRED");
	}

	@Test
	void 이미_확정된_예약에_새_키로_결제하면_INVALID_STATE() {
		long id = reserve();
		pay(id, "pay-1", "");

		Response r = pay(id, "pay-2", "");

		assertThat(r.status()).isEqualTo(409);
		assertThat(r.<String>json("$.code")).isEqualTo("INVALID_STATE");
	}

	@Test
	void 다른_사람의_예약은_결제할_수_없고_없는_예약은_404() {
		long id = reserve();

		assertThat(pay(id, "intruder", "pay-1", "").<String>json("$.code")).isEqualTo("NOT_OWNER");
		assertThat(pay(9999L, "pay-2", "").status()).isEqualTo(404);
		assertThat(count("SELECT COUNT(*) FROM payment")).isZero();
	}

	@Test
	void PG_응답이_타임아웃되면_PROCESSING_이고_상태_확정_스케줄러가_PG_조회로_확정한다() {
		long id = reserve();

		// PG 는 승인을 기록한 뒤 2.5초 후 응답 → 우리 읽기 타임아웃(1.5초)이 먼저 끝난다
		Response r = pay(id, "pay-1", "?delayMs=2500");

		assertThat(r.<String>json("$.result")).isEqualTo("PROCESSING");
		assertThat(r.<String>json("$.paymentStatus")).isEqualTo("READY");
		assertThat(reservationStatus(id)).isEqualTo("PENDING");

		jdbc.update("UPDATE payment SET created_at = now() - interval '2 minutes'"); // 1분 넘게 READY
		int checked = paymentService.reconcileStaleReady();

		assertThat(checked).isEqualTo(1);
		assertThat(reservationStatus(id)).isEqualTo("CONFIRMED");
		assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE to_status = 'CONFIRMED' "
				+ "AND actor = 'system:payment-reconcile'")).isEqualTo(1);
	}

	@Test
	void 타임아웃_후_같은_키로_재시도하면_같은_주문번호로_PG_결과를_받아_확정한다() throws Exception {
		long id = reserve();
		assertThat(pay(id, "pay-1", "?delayMs=2500").<String>json("$.result")).isEqualTo("PROCESSING");
		Thread.sleep(1200); // 첫 PG 요청의 응답 지연이 끝나도록

		Response retried = pay(id, "pay-1", "");

		assertThat(retried.<String>json("$.result")).isEqualTo("CONFIRMED");
		assertThat(count("SELECT COUNT(*) FROM payment")).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM mockpg_payment")).isEqualTo(1); // 이중 승인 없음
	}

	@Test
	void PG_에_기록이_없는_오래된_READY_결제는_실패로_확정된다() {
		long id = reserve();
		jdbc.update("INSERT INTO payment (reservation_id, pg_order_id, amount, status, idempotency_key, created_at) "
				+ "VALUES (?, 'P-LOST', ?, 'READY', 'pay-lost', now() - interval '2 minutes')", id, PRICE);

		paymentService.reconcileStaleReady();

		assertThat(jdbc.queryForObject("SELECT status FROM payment WHERE pg_order_id = 'P-LOST'", String.class))
				.isEqualTo("FAILED");
		assertThat(reservationStatus(id)).isEqualTo("PENDING");
	}

	private static List<Response> concurrently(int threads, IntFunction<Response> call) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Response>> futures = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			int index = i;
			futures.add(executor.submit(() -> {
				start.await();
				return call.apply(index);
			}));
		}
		start.countDown();
		List<Response> results = new ArrayList<>();
		for (Future<Response> f : futures) {
			results.add(f.get(30, TimeUnit.SECONDS));
		}
		executor.shutdown();
		return results;
	}
}
