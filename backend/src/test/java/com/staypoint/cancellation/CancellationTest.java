package com.staypoint.cancellation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
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
import com.staypoint.support.ApiIntegrationTest;
import com.staypoint.support.HttpTestClient.Response;

/**
 * 예약 취소 · 환불 (설계 4.5, FR-CAN-1~7, S9). 환불은 같은 앱의 모의 PG 를 HTTP 로 부른다.
 * 기본 체크인은 오늘 + 10일 → 환불율 100%.
 */
class CancellationTest extends ApiIntegrationTest {

	@Autowired
	HoldExpiryService holdExpiryService;

	@Test
	void 확정_예약을_7일_넘게_남기고_취소하면_전액_환불되고_재고가_복원된다() {
		long id = confirmedReservation();
		assertThat(fixtures.bookedCount(roomTypeId, checkIn)).isEqualTo(1);

		Response r = cancel(id, USER);

		assertThat(r.status()).isEqualTo(200);
		assertThat(r.<String>json("$.status")).isEqualTo("CANCELED");
		assertThat(r.<String>json("$.cancelReason")).isEqualTo("USER_CANCEL");
		assertThat(r.<Integer>json("$.refundAmount")).isEqualTo(PRICE);
		assertThat(r.<String>json("$.refundStatus")).isEqualTo("SUCCEEDED");
		assertThat(fixtures.bookedCount(roomTypeId, checkIn)).isZero();
		assertThat(jdbc.queryForObject("SELECT status FROM payment", String.class)).isEqualTo("CANCELED");
		assertThat(jdbc.queryForObject("SELECT status FROM mockpg_payment", String.class)).isEqualTo("CANCELED");
		assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE reservation_id = ? AND from_status = 'CONFIRMED' "
				+ "AND to_status = 'CANCELED' AND reason = 'USER_CANCEL' AND actor = 'user:user-1'", id)).isEqualTo(1);
	}

	@Test
	void 체크인_5일_전_취소는_70퍼센트만_부분_환불한다() {
		LocalDate in = LocalDate.now(clock).plusDays(5);
		long room = fixtures.roomType(2, 5, PRICE, in, in.plusDays(1));
		long id = confirmedReservation(room, in);

		Response r = cancel(id, USER);

		assertThat(r.<Integer>json("$.refundAmount")).isEqualTo(70_000);
		// 부분 취소라 결제는 APPROVED 로 남고 취소 금액만 기록된다
		assertThat(jdbc.queryForObject("SELECT status || '/' || canceled_amount FROM payment", String.class))
				.isEqualTo("APPROVED/70000");
		assertThat(jdbc.queryForObject("SELECT canceled_amount FROM mockpg_payment", Integer.class)).isEqualTo(70_000);
	}

	@Test
	void 체크인_당일_취소는_환불이_0원이고_PG_를_부르지_않는다() {
		LocalDate today = LocalDate.now(clock);
		long room = fixtures.roomType(2, 5, PRICE, today, today.plusDays(1));
		long id = confirmedReservation(room, today);

		Response r = cancel(id, USER);

		assertThat(r.<String>json("$.status")).isEqualTo("CANCELED");
		assertThat(r.<Integer>json("$.refundAmount")).isZero();
		assertThat(r.<String>json("$.refundStatus")).isEqualTo("NONE");
		assertThat(count("SELECT COUNT(*) FROM payment_cancel")).isZero();
		assertThat(count("SELECT COUNT(*) FROM mockpg_cancel")).isZero();
		assertThat(fixtures.bookedCount(room, today)).isZero(); // 재고는 복원
	}

	@Test
	void 결제_전_PENDING_예약_취소는_환불_없이_재고만_복원한다() {
		long id = reserve();

		Response r = cancel(id, USER);

		assertThat(r.<String>json("$.status")).isEqualTo("CANCELED");
		assertThat(r.<Integer>json("$.refundAmount")).isZero();
		assertThat(r.<String>json("$.refundStatus")).isEqualTo("NONE");
		assertThat(fixtures.bookedCount(roomTypeId, checkIn)).isZero();
		assertThat(count("SELECT COUNT(*) FROM mockpg_cancel")).isZero();
	}

	@Test
	void 이미_취소된_예약을_다시_취소하면_처음_결과를_돌려주고_재고와_환불은_한_번만_처리된다() {
		reserve(); // 같은 날짜의 다른 예약 → 재고가 두 번 복원되면 booked 가 0 이 되어 드러난다
		long id = confirmedReservation();
		assertThat(fixtures.bookedCount(roomTypeId, checkIn)).isEqualTo(2);

		Response first = cancel(id, USER);
		Response second = cancel(id, USER);

		assertThat(second.status()).isEqualTo(200);
		assertThat(second.body()).isEqualTo(first.body());
		assertThat(fixtures.bookedCount(roomTypeId, checkIn)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM payment_cancel")).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM mockpg_cancel")).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE reservation_id = ? AND to_status = 'CANCELED'",
				id)).isEqualTo(1);
	}

	@Test
	void 동시에_3번_취소해도_한_번만_처리되고_모두_같은_결과를_받는다() throws Exception {
		reserve();
		long id = confirmedReservation();

		ExecutorService executor = Executors.newFixedThreadPool(3);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Response>> futures = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			futures.add(executor.submit(() -> {
				start.await();
				return cancel(id, USER);
			}));
		}
		start.countDown();
		List<Response> responses = new ArrayList<>();
		for (Future<Response> f : futures) {
			responses.add(f.get(30, TimeUnit.SECONDS));
		}
		executor.shutdown();

		assertThat(responses).extracting(Response::status).containsOnly(200);
		assertThat(new HashSet<>(responses.stream().map(r -> r.<String>json("$.canceledAt")).toList())).hasSize(1);
		assertThat(fixtures.bookedCount(roomTypeId, checkIn)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM payment_cancel")).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT canceled_amount FROM mockpg_payment", Integer.class)).isEqualTo(PRICE);
	}

	@Test
	void 선점_만료로_취소된_예약을_취소하면_만료_결과를_그대로_돌려준다() {
		long id = reserve();
		setHoldExpiresIn(id, -1);
		holdExpiryService.expireNext();

		Response r = cancel(id, USER);

		assertThat(r.status()).isEqualTo(200);
		assertThat(r.<String>json("$.cancelReason")).isEqualTo("HOLD_EXPIRED");
		assertThat(fixtures.bookedCount(roomTypeId, checkIn)).isZero(); // 한 번만 복원 (음수 아님)
	}

	@Test
	void 결제_진행_중에_취소하면_나중에_온_승인은_확정되지_않고_보상_취소된다() throws Exception {
		long id = reserve();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		Future<Response> payment = executor.submit(() -> pay(id, "pay-1", "?delayMs=1000"));
		awaitReadyPayment();

		Response cancelled = cancel(id, USER);
		Response paid = payment.get(10, TimeUnit.SECONDS);
		executor.shutdown();

		assertThat(cancelled.<String>json("$.status")).isEqualTo("CANCELED");
		assertThat(cancelled.<Integer>json("$.refundAmount")).isZero(); // 취소 시점엔 승인 전이라 환불 대상 없음
		assertThat(paid.<String>json("$.result")).isEqualTo("COMPENSATED");
		assertThat(jdbc.queryForObject("SELECT status FROM payment", String.class)).isEqualTo("CANCELED");
		assertThat(jdbc.queryForObject("SELECT status FROM mockpg_payment", String.class)).isEqualTo("CANCELED");
		assertThat(reservationStatus(id)).isEqualTo("CANCELED");
	}

	@Test
	void PG_가_환불을_거절해도_취소는_성공하고_운영자_확인_대상으로_남는다() {
		long id = confirmedReservation();
		jdbc.update("UPDATE mockpg_payment SET tid = 'T-GONE'"); // PG 쪽에서 결제를 찾을 수 없게 됨 → 404

		Response r = cancel(id, USER);

		assertThat(r.status()).isEqualTo(200);
		assertThat(r.<String>json("$.status")).isEqualTo("CANCELED");
		assertThat(r.<String>json("$.refundStatus")).isEqualTo("MANUAL_REVIEW");
		assertThat(jdbc.queryForObject("SELECT status FROM payment", String.class)).isEqualTo("APPROVED");
		assertThat(fixtures.bookedCount(roomTypeId, checkIn)).isZero();
	}

	@Test
	void 다른_사람의_예약은_취소할_수_없고_없는_예약은_404() {
		long id = confirmedReservation();

		Response r = cancel(id, "intruder");

		assertThat(r.status()).isEqualTo(403);
		assertThat(r.<String>json("$.code")).isEqualTo("NOT_OWNER");
		assertThat(reservationStatus(id)).isEqualTo("CONFIRMED");
		assertThat(cancel(9999L, USER).status()).isEqualTo(404);
	}

	@Test
	void 이용이_끝난_예약은_취소할_수_없다() {
		long id = confirmedReservation();
		jdbc.update("UPDATE reservation SET status = 'COMPLETED' WHERE id = ?", id);

		Response r = cancel(id, USER);

		assertThat(r.status()).isEqualTo(409);
		assertThat(r.<String>json("$.code")).isEqualTo("INVALID_STATE");
		assertThat(fixtures.bookedCount(roomTypeId, checkIn)).isEqualTo(1);
	}

	private long confirmedReservation() {
		return confirmedReservation(roomTypeId, checkIn);
	}

	private long confirmedReservation(long room, LocalDate in) {
		long id = reserve(room, in);
		assertThat(pay(id, "pay-" + id, "").<String>json("$.result")).isEqualTo("CONFIRMED");
		return id;
	}

	private Response cancel(long reservationId, String userId) {
		return http.post("/api/reservations/" + reservationId + "/cancel", Map.of("X-User-Id", userId), null);
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
