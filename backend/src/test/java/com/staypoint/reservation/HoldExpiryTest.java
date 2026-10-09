package com.staypoint.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.staypoint.TestcontainersConfiguration;
import com.staypoint.reservation.dto.CreateReservationRequest;
import com.staypoint.support.TestFixtures;

/**
 * 선점 만료 (설계 4.7, FR-RES-4). 만료 시각은 DB 에서 과거로 옮겨 "10분이 지난 상황" 을 만든다.
 * 테스트에서는 스케줄러가 꺼져 있으므로(test config/application.yml) 만료 처리를 직접 호출한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class HoldExpiryTest {

	@Autowired
	ReservationService reservationService;

	@Autowired
	HoldExpiryService holdExpiryService;

	@Autowired
	HoldExpiryScheduler holdExpiryScheduler;

	@Autowired
	ReservationHistoryRepository historyRepository;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Clock clock;

	TestFixtures fixtures;
	LocalDate checkIn;
	LocalDate checkOut;
	long roomTypeId;

	@BeforeEach
	void setUp() {
		fixtures = new TestFixtures(jdbc);
		fixtures.truncateAll();
		checkIn = LocalDate.now(clock).plusDays(10);
		checkOut = checkIn.plusDays(2);
		roomTypeId = fixtures.roomType(2, 10, 100_000, checkIn, checkOut);
	}

	@Test
	void 만료된_PENDING_예약은_CANCELED_HOLD_EXPIRED_가_되고_모든_날짜의_재고가_복원된다() {
		long id = reserve("user-1");
		assertThat(fixtures.bookedCount(roomTypeId, checkIn)).isEqualTo(1);
		passHoldTime(id);

		assertThat(holdExpiryService.expireNext()).isTrue();

		assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, id))
				.isEqualTo("CANCELED");
		assertThat(jdbc.queryForObject("SELECT cancel_reason FROM reservation WHERE id = ?", String.class, id))
				.isEqualTo("HOLD_EXPIRED");
		assertThat(jdbc.queryForObject("SELECT canceled_at IS NOT NULL FROM reservation WHERE id = ?", Boolean.class, id))
				.isTrue();
		assertThat(fixtures.bookedCount(roomTypeId, checkIn)).isZero();
		assertThat(fixtures.bookedCount(roomTypeId, checkIn.plusDays(1))).isZero();

		List<ReservationHistory> history = historyRepository.findByReservationIdOrderByIdAsc(id);
		assertThat(history).hasSize(2);
		ReservationHistory expired = history.get(1);
		assertThat(expired.getFromStatus()).isEqualTo(ReservationStatus.PENDING);
		assertThat(expired.getToStatus()).isEqualTo(ReservationStatus.CANCELED);
		assertThat(expired.getReason()).isEqualTo("HOLD_EXPIRED");
		assertThat(expired.getActor()).isEqualTo(HoldExpiryService.ACTOR);

		assertThat(holdExpiryService.expireNext()).isFalse(); // 더 처리할 것이 없다
	}

	@Test
	void 만료_시각이_지나지_않은_예약은_건드리지_않는다() {
		long id = reserve("user-1");

		assertThat(holdExpiryService.expireNext()).isFalse();

		assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, id))
				.isEqualTo("PENDING");
		assertThat(fixtures.bookedCount(roomTypeId, checkIn)).isEqualTo(1);
	}

	@Test
	void 확정된_예약은_만료_시각이_지났어도_대상이_아니다() {
		long id = reserve("user-1");
		passHoldTime(id);
		jdbc.update("UPDATE reservation SET status = 'CONFIRMED' WHERE id = ?", id); // 결제 확정이 먼저 된 상황

		assertThat(holdExpiryService.expireNext()).isFalse();
		assertThat(fixtures.bookedCount(roomTypeId, checkIn)).isEqualTo(1);
	}

	@Test
	void 만료로_풀린_재고는_다른_사람이_다시_예약할_수_있다() {
		jdbc.update("UPDATE room_inventory SET total_count = 1 WHERE room_type_id = ?", roomTypeId); // 마지막 1실
		long first = reserve("user-1");
		passHoldTime(first);

		holdExpiryScheduler.expireExpiredHolds();
		reserve("user-2");

		assertThat(fixtures.bookedCount(roomTypeId, checkIn)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE status = 'PENDING'", Integer.class))
				.isEqualTo(1);
	}

	@Test
	void 재고가_이미_어긋나_0이어도_만료는_진행되고_음수가_되지_않는다() {
		long id = reserve("user-1");
		passHoldTime(id);
		fixtures.setBooked(roomTypeId, checkIn, 0); // 수동 수정 등으로 이미 어긋난 데이터

		assertThat(holdExpiryService.expireNext()).isTrue();

		assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, id))
				.isEqualTo("CANCELED");
		assertThat(fixtures.bookedCount(roomTypeId, checkIn)).isZero();             // 음수로 내려가지 않음
		assertThat(fixtures.bookedCount(roomTypeId, checkIn.plusDays(1))).isZero(); // 정상 날짜는 복원
	}

	@Test
	void 스케줄러_3개가_동시에_돌아도_만료된_예약_5건은_각각_한_번씩만_처리된다() throws Exception {
		for (int i = 0; i < 5; i++) {
			passHoldTime(reserve("user-" + i));
		}
		assertThat(fixtures.bookedCount(roomTypeId, checkIn)).isEqualTo(5);

		int threads = 3;
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			results.add(executor.submit(() -> {
				start.await();
				return holdExpiryScheduler.expireExpiredHolds();
			}));
		}
		start.countDown();
		int total = 0;
		for (Future<Integer> f : results) {
			total += f.get(30, TimeUnit.SECONDS);
		}
		executor.shutdown();

		// SKIP LOCKED 로 나눠 가져가므로 합계가 정확히 5, 재고도 정확히 5만큼만 복원
		assertThat(total).isEqualTo(5);
		assertThat(fixtures.bookedCount(roomTypeId, checkIn)).isZero();
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM reservation_history WHERE reason = 'HOLD_EXPIRED'", Integer.class)).isEqualTo(5);
	}

	private long reserve(String userId) {
		return reservationService.create(userId, "key-" + userId,
				new CreateReservationRequest(roomTypeId, checkIn, checkOut, 2, "홍길동", "010-0000-0000"))
				.reservation().id();
	}

	/** 선점 10분이 지난 상황을 만든다. */
	private void passHoldTime(long reservationId) {
		jdbc.update("UPDATE reservation SET hold_expires_at = now() - interval '1 second' WHERE id = ?", reservationId);
	}
}
