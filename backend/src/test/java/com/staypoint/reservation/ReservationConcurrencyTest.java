package com.staypoint.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.staypoint.TestcontainersConfiguration;
import com.staypoint.common.error.BusinessException;
import com.staypoint.common.error.ErrorCode;
import com.staypoint.reservation.dto.CreateReservationRequest;
import com.staypoint.support.TestFixtures;

/**
 * 동시 예약 테스트 (설계 10장 S2 · FR-RES-3 · FR-RES-6). 실제 PostgreSQL(Testcontainers)에서 실행한다.
 * 모든 스레드를 CountDownLatch 로 붙잡아 두었다가 한 번에 출발시켜 같은 재고 행을 동시에 노리게 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReservationConcurrencyTest {

	@Autowired
	ReservationService reservationService;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Clock clock;

	TestFixtures fixtures;
	LocalDate checkIn;
	LocalDate checkOut;

	@BeforeEach
	void setUp() {
		fixtures = new TestFixtures(jdbc);
		fixtures.truncateAll();
		checkIn = LocalDate.now(clock).plusDays(10);
		checkOut = checkIn.plusDays(2); // 2박 → 재고 행 2개를 동시에 차감
	}

	@Test
	void 재고_3실에_50명이_동시에_예약하면_정확히_3명만_성공한다() throws Exception {
		int stock = 3;
		int requests = 50;
		long roomTypeId = fixtures.roomType(2, stock, 100_000, checkIn, checkOut);

		AtomicInteger success = new AtomicInteger();
		AtomicInteger soldOut = new AtomicInteger();
		List<Throwable> unexpected = new ArrayList<>();

		runConcurrently(requests, i -> {
			try {
				reservationService.create("user-" + i, "key-" + i, request(roomTypeId));
				success.incrementAndGet();
			} catch (BusinessException e) {
				if (e.getErrorCode() == ErrorCode.SOLD_OUT) {
					soldOut.incrementAndGet();
				} else {
					synchronized (unexpected) {
						unexpected.add(e);
					}
				}
			} catch (Throwable t) {
				synchronized (unexpected) {
					unexpected.add(t);
				}
			}
		});

		System.out.printf("[동시 예약] 재고=%d, 요청=%d → 성공=%d, SOLD_OUT=%d, 기타 오류=%d, booked_count(%s)=%d, booked_count(%s)=%d%n",
				stock, requests, success.get(), soldOut.get(), unexpected.size(),
				checkIn, fixtures.bookedCount(roomTypeId, checkIn),
				checkIn.plusDays(1), fixtures.bookedCount(roomTypeId, checkIn.plusDays(1)));

		assertThat(unexpected).isEmpty();
		assertThat(success.get()).isEqualTo(stock);
		assertThat(soldOut.get()).isEqualTo(requests - stock);
		assertThat(fixtures.bookedCount(roomTypeId, checkIn)).isEqualTo(stock);
		assertThat(fixtures.bookedCount(roomTypeId, checkIn.plusDays(1))).isEqualTo(stock);
		assertThat(fixtures.count("reservation")).isEqualTo(stock);
		assertThat(fixtures.count("reservation_history")).isEqualTo(stock);
	}

	/**
	 * 연박 부분 겹침 (T25): 서로 다른 날짜 구간이 일부만 겹치는 예약들이 동시에 들어와도
	 * ① 어느 날짜도 재고를 넘지 않고 ② booked_count 가 그 날짜를 쓰는 예약 수와 정확히 같고
	 * ③ 데드락 등 예상 밖 오류가 없어야 한다 (여러 날짜 행을 잠그는 순서가 요청마다 다르면 데드락이 난다).
	 */
	@Test
	void 날짜_구간이_일부만_겹치는_연박_예약이_동시에_와도_날짜별_재고를_넘지_않고_데드락이_없다() throws Exception {
		int stock = 2;
		int requests = 40;
		LocalDate d0 = checkIn;
		long roomTypeId = fixtures.roomType(2, stock, 100_000, d0, d0.plusDays(4)); // d0 ~ d3, 날짜마다 2실
		// [체크인, 체크아웃) 구간 — 서로 앞뒤로 엇갈리게 겹친다
		LocalDate[][] ranges = {
				{ d0, d0.plusDays(2) },             // d0 d1
				{ d0.plusDays(1), d0.plusDays(3) }, //    d1 d2
				{ d0.plusDays(2), d0.plusDays(4) }, //       d2 d3
				{ d0, d0.plusDays(4) },             // d0 d1 d2 d3
				{ d0.plusDays(1), d0.plusDays(2) }, //    d1
		};

		AtomicInteger success = new AtomicInteger();
		AtomicInteger soldOut = new AtomicInteger();
		List<Throwable> unexpected = new ArrayList<>();

		runConcurrently(requests, i -> {
			LocalDate[] range = ranges[i % ranges.length];
			try {
				reservationService.create("user-" + i, "key-" + i,
						new CreateReservationRequest(roomTypeId, range[0], range[1], 2, "홍길동", "010-0000-0000"));
				success.incrementAndGet();
			} catch (BusinessException e) {
				if (e.getErrorCode() == ErrorCode.SOLD_OUT) {
					soldOut.incrementAndGet();
				} else {
					synchronized (unexpected) {
						unexpected.add(e);
					}
				}
			} catch (Throwable t) {
				synchronized (unexpected) {
					unexpected.add(t); // 데드락이면 PostgreSQL 이 한쪽을 중단시켜 여기로 온다
				}
			}
		});

		StringBuilder perDate = new StringBuilder();
		for (int day = 0; day < 4; day++) {
			LocalDate date = d0.plusDays(day);
			int booked = fixtures.bookedCount(roomTypeId, date);
			// 정답 = 그 날짜를 쓰는 예약 수 (설계 6장 정합 기준)
			int expected = jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE room_type_id = ? "
					+ "AND check_in <= ? AND check_out > ?", Integer.class, roomTypeId, date, date);
			perDate.append(String.format(" %s=%d/%d", date, booked, stock));
			assertThat(booked).as("booked_count(%s)", date).isEqualTo(expected).isLessThanOrEqualTo(stock);
		}
		System.out.printf("[연박 부분 겹침] 날짜별 재고=%d, 요청=%d → 성공=%d, SOLD_OUT=%d, 기타 오류=%d, booked_count:%s%n",
				stock, requests, success.get(), soldOut.get(), unexpected.size(), perDate);

		assertThat(unexpected).isEmpty();
		assertThat(success.get()).isPositive();
		assertThat(success.get() + soldOut.get()).isEqualTo(requests);
		assertThat(fixtures.count("reservation")).isEqualTo(success.get());
	}

	@Test
	void 같은_멱등키로_동시에_두번_요청해도_예약은_하나만_생긴다() throws Exception {
		long roomTypeId = fixtures.roomType(2, 5, 100_000, checkIn, checkOut);
		Set<Long> reservationIds = ConcurrentHashMap.newKeySet();
		AtomicInteger created = new AtomicInteger();

		runConcurrently(2, i -> {
			ReservationService.CreateResult result = reservationService.create("user-1", "same-key", request(roomTypeId));
			reservationIds.add(result.reservation().id());
			if (result.created()) {
				created.incrementAndGet();
			}
		});

		assertThat(reservationIds).hasSize(1);          // 두 요청 모두 같은 예약을 받는다
		assertThat(created.get()).isEqualTo(1);          // 실제로 만든 것은 한 번
		assertThat(fixtures.count("reservation")).isEqualTo(1);
		assertThat(fixtures.bookedCount(roomTypeId, checkIn)).isEqualTo(1); // 재고도 한 번만 차감
	}

	private CreateReservationRequest request(long roomTypeId) {
		return new CreateReservationRequest(roomTypeId, checkIn, checkOut, 2, "홍길동", "010-0000-0000");
	}

	interface Task {
		void run(int index) throws Exception;
	}

	/** threads 개 작업을 준비시킨 뒤 동시에 출발시키고, 모두 끝날 때까지 기다린다. 작업 안의 예외는 다시 던진다. */
	private static void runConcurrently(int threads, Task task) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> futures = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			int index = i;
			futures.add(executor.submit(() -> {
				ready.countDown();
				start.await();
				task.run(index);
				return null;
			}));
		}
		ready.await();
		start.countDown();
		for (Future<?> f : futures) {
			f.get(30, TimeUnit.SECONDS);
		}
		executor.shutdown();
	}
}
