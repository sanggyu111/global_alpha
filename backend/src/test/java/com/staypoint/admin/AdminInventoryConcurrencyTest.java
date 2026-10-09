package com.staypoint.admin;

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
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.staypoint.TestcontainersConfiguration;
import com.staypoint.common.error.BusinessException;
import com.staypoint.common.error.ErrorCode;
import com.staypoint.reservation.ReservationService;
import com.staypoint.reservation.dto.CreateReservationRequest;
import com.staypoint.support.TestFixtures;

/**
 * 관리자 재고 축소와 예약이 동시에 일어나도 booked_count 가 total_count 를 넘지 않는다.
 * 순서는 매번 달라질 수 있으므로 결과 조합이 아니라 "불변식" 을 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminInventoryConcurrencyTest {

	@Autowired
	AdminInventoryService adminInventoryService;

	@Autowired
	ReservationService reservationService;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Clock clock;

	TestFixtures fixtures;
	LocalDate in;
	long roomTypeId;

	@BeforeEach
	void setUp() {
		fixtures = new TestFixtures(jdbc);
		fixtures.truncateAll();
		in = LocalDate.now(clock).plusDays(10);
		roomTypeId = fixtures.roomType(2, 5, 100_000, in, in.plusDays(1)); // 재고 5
	}

	@RepeatedTest(3)
	void 재고를_2로_줄이는_동안_10명이_예약해도_예약_수는_최종_재고를_넘지_않는다() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(11);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<String>> futures = new ArrayList<>();
		futures.add(executor.submit(() -> {
			start.await();
			try {
				adminInventoryService.setInventory(roomTypeId, in, in, 2);
				return "ADMIN_OK";
			} catch (BusinessException e) {
				return "ADMIN_" + e.getErrorCode(); // 이미 3건 넘게 예약된 뒤라면 거부
			}
		}));
		for (int i = 0; i < 10; i++) {
			int n = i;
			futures.add(executor.submit(() -> {
				start.await();
				try {
					reservationService.create("u-" + n, "k-" + n,
							new CreateReservationRequest(roomTypeId, in, in.plusDays(1), 2, "홍길동", "010"));
					return "RESERVED";
				} catch (BusinessException e) {
					return e.getErrorCode().name();
				}
			}));
		}
		start.countDown();
		List<String> results = new ArrayList<>();
		for (Future<String> f : futures) {
			results.add(f.get(30, TimeUnit.SECONDS));
		}
		executor.shutdown();

		int total = jdbc.queryForObject("SELECT total_count FROM room_inventory WHERE room_type_id = ?", Integer.class, roomTypeId);
		int booked = fixtures.bookedCount(roomTypeId, in);
		long reserved = results.stream().filter("RESERVED"::equals).count();

		assertThat(results).allMatch(r -> r.equals("RESERVED") || r.equals(ErrorCode.SOLD_OUT.name())
				|| r.equals("ADMIN_OK") || r.equals("ADMIN_" + ErrorCode.INVENTORY_BELOW_BOOKED));
		assertThat(booked).isEqualTo(reserved).isLessThanOrEqualTo(total);
		assertThat(total).isEqualTo(results.contains("ADMIN_OK") ? 2 : 5);
	}
}
