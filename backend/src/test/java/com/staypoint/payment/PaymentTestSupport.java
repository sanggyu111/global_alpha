package com.staypoint.payment;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.staypoint.TestcontainersConfiguration;
import com.staypoint.reservation.ReservationService;
import com.staypoint.reservation.dto.CreateReservationRequest;
import com.staypoint.support.HttpTestClient;
import com.staypoint.support.TestFixtures;

/**
 * 결제 통합 테스트 공통 준비. 결제 API 가 같은 앱의 모의 PG 를 HTTP 로 부르므로 랜덤 포트로 실제 서버를 띄운다.
 * 테스트 설정(config/application.yml): PG 기본 실패율 0 · 지연 0, PG 읽기 타임아웃 1.5초, 웹훅 전송 꺼짐.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
abstract class PaymentTestSupport {

	static final int PRICE = 100_000;
	static final String USER = "user-1";

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ReservationService reservationService;

	@Autowired
	Clock clock;

	TestFixtures fixtures;
	HttpTestClient http;
	LocalDate checkIn;
	long roomTypeId;

	@BeforeEach
	void setUpPaymentTest() {
		fixtures = new TestFixtures(jdbc);
		fixtures.truncateAll();
		http = new HttpTestClient(port);
		checkIn = LocalDate.now(clock).plusDays(10);
		roomTypeId = fixtures.roomType(2, 5, PRICE, checkIn, checkIn.plusDays(5));
	}

	/** 1박 예약(PENDING)을 만들고 id 를 돌려준다. */
	long reserve() {
		return reservationService.create(USER, "res-" + System.nanoTime(),
				new CreateReservationRequest(roomTypeId, checkIn, checkIn.plusDays(1), 2, "홍길동", "010-0000-0000"))
				.reservation().id();
	}

	HttpTestClient.Response pay(long reservationId, String key, String query) {
		return pay(reservationId, USER, key, query);
	}

	HttpTestClient.Response pay(long reservationId, String userId, String key, String query) {
		return http.post("/api/reservations/" + reservationId + "/payments" + query,
				Map.of("X-User-Id", userId, "Idempotency-Key", key), null);
	}

	String reservationStatus(long reservationId) {
		return jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservationId);
	}

	int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	void setHoldExpiresIn(long reservationId, int seconds) {
		jdbc.update("UPDATE reservation SET hold_expires_at = now() + make_interval(secs => ?) WHERE id = ?",
				seconds, reservationId);
	}
}
