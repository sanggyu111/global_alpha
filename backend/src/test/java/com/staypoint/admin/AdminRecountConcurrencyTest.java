package com.staypoint.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.staypoint.TestcontainersConfiguration;
import com.staypoint.admin.AdminDtos.RecountResult;
import com.staypoint.cancellation.CancellationService;
import com.staypoint.reservation.ReservationService;
import com.staypoint.reservation.dto.CreateReservationRequest;
import com.staypoint.support.TestFixtures;

/**
 * 재계산(recount)이 예약 생성·취소와 겹쳐도 끝난 뒤 booked_count = 활성 예약 수 (README Q6, T26).
 *
 * <p>재계산은 재고 행을 잠근 뒤 센다. 잠금 없이 세면, 재고는 올렸지만 아직 커밋 전인 예약을 놓친 채
 * 옛 값으로 덮어써 그 예약 몫이 사라진다 — 이 테스트가 잡으려는 경우.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminRecountConcurrencyTest {

	private static final int TOTAL = 20;
	private static final int EXISTING = 4; // 미리 만들어 두고 동시에 취소할 예약
	private static final int NEW = 8;      // 동시에 새로 만들 예약
	private static final int SKEWED = 10;  // 실제(4)보다 크게 틀어 둔 booked_count — 취소 복원이 빠진 버그를 흉내

	@Autowired
	AdminInventoryService adminInventoryService;

	@Autowired
	ReservationService reservationService;

	@Autowired
	CancellationService cancellationService;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Clock clock;

	TestFixtures fixtures;
	LocalDate in;
	long roomTypeId;
	List<Long> existingIds;

	@BeforeEach
	void setUp() {
		fixtures = new TestFixtures(jdbc);
		fixtures.truncateAll();
		in = LocalDate.now(clock).plusDays(10);
		roomTypeId = fixtures.roomType(2, TOTAL, 100_000, in, in.plusDays(1));
		existingIds = new ArrayList<>();
		for (int i = 0; i < EXISTING; i++) {
			existingIds.add(reservationService.create("old-" + i, "old-k-" + i, request()).reservation().id());
		}
		fixtures.setBooked(roomTypeId, in, SKEWED);
	}

	@RepeatedTest(5)
	void 재계산과_예약_생성_취소가_동시에_실행돼도_끝난_뒤_판매수는_활성_예약_수와_같다() throws Exception {
		List<Callable<String>> tasks = new ArrayList<>();
		tasks.add(() -> {
			RecountResult r = adminInventoryService.recount(roomTypeId, in);
			return "RECOUNT " + r.before() + "→" + r.after();
		});
		for (int i = 0; i < NEW; i++) {
			int n = i;
			tasks.add(() -> {
				reservationService.create("new-" + n, "new-k-" + n, request());
				return "RESERVED";
			});
		}
		for (int i = 0; i < EXISTING; i++) {
			int n = i;
			tasks.add(() -> {
				cancellationService.cancel(existingIds.get(n), "old-" + n, null);
				return "CANCELED";
			});
		}

		List<String> results = runConcurrently(tasks);

		// 재고 20 에 활성 예약은 최대 12 → 매진·재계산 거부 없이 모두 성공해야 한다
		assertThat(results).filteredOn(r -> r.startsWith("RECOUNT")).hasSize(1);
		assertThat(results).filteredOn("RESERVED"::equals).hasSize(NEW);
		assertThat(results).filteredOn("CANCELED"::equals).hasSize(EXISTING);
		assertThat(activeReservations()).isEqualTo(NEW);
		assertThat(fixtures.bookedCount(roomTypeId, in)).isEqualTo(activeReservations());
		assertThat(adminInventoryService.mismatches(in, in)).isEmpty();
	}

	private List<String> runConcurrently(List<Callable<String>> tasks) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
		CountDownLatch start = new CountDownLatch(1);
		List<Future<String>> futures = new ArrayList<>();
		for (Callable<String> task : tasks) {
			futures.add(executor.submit(() -> {
				start.await();
				return task.call();
			}));
		}
		start.countDown();
		List<String> results = new ArrayList<>();
		for (Future<String> f : futures) {
			results.add(f.get(30, TimeUnit.SECONDS)); // 예외가 나면 ExecutionException 으로 테스트 실패
		}
		executor.shutdown();
		return results;
	}

	private int activeReservations() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE room_type_id = ? "
				+ "AND status IN ('PENDING', 'CONFIRMED', 'COMPLETED')", Integer.class, roomTypeId);
	}

	private CreateReservationRequest request() {
		return new CreateReservationRequest(roomTypeId, in, in.plusDays(1), 2, "홍길동", "010");
	}
}
