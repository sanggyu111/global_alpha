package com.staypoint.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.staypoint.TestcontainersConfiguration;
import com.staypoint.support.TestFixtures;

/**
 * POST /api/reservations — 예약 생성 · 재고 선점 · 입력 검증 · 멱등 재요청 (설계 4.2, FR-RES-1~6).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReservationCreateApiTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ReservationHistoryRepository historyRepository;

	@Autowired
	Clock clock;

	TestFixtures fixtures;
	LocalDate checkIn;
	long roomTypeId;

	@BeforeEach
	void setUp() {
		fixtures = new TestFixtures(jdbc);
		fixtures.truncateAll();
		checkIn = LocalDate.now(clock).plusDays(10);
		// 정원 2명, 재고 5실, 1박 100,000원. 체크인부터 5일치 재고·요금
		roomTypeId = fixtures.roomType(2, 5, 100_000, checkIn, checkIn.plusDays(5));
	}

	@Test
	void 예약을_만들면_PENDING_상태로_날짜별_요금_합계와_선점_만료시각이_저장되고_재고가_차감된다() throws Exception {
		fixtures.setPrice(roomTypeId, checkIn.plusDays(1), 130_000); // 둘째 날만 주말 요금

		createReservation("user-1", "key-1", body(checkIn, checkIn.plusDays(3), 2))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.reservationNo").isNotEmpty())
				.andExpect(jsonPath("$.totalAmount").value(330_000));

		// 3박 → 체크인 ~ 체크아웃 전날 3개 행만 차감, 체크아웃 날짜는 그대로
		assertThat(fixtures.bookedCount(roomTypeId, checkIn)).isEqualTo(1);
		assertThat(fixtures.bookedCount(roomTypeId, checkIn.plusDays(2))).isEqualTo(1);
		assertThat(fixtures.bookedCount(roomTypeId, checkIn.plusDays(3))).isZero();

		Long holdSeconds = jdbc.queryForObject(
				"SELECT EXTRACT(EPOCH FROM hold_expires_at - created_at)::bigint FROM reservation", Long.class);
		assertThat(holdSeconds).isEqualTo(Duration.ofMinutes(10).toSeconds());

		Long reservationId = jdbc.queryForObject("SELECT id FROM reservation", Long.class);
		List<ReservationHistory> history = historyRepository.findByReservationIdOrderByIdAsc(reservationId);
		assertThat(history).hasSize(1);
		assertThat(history.get(0).getFromStatus()).isNull();
		assertThat(history.get(0).getToStatus()).isEqualTo(ReservationStatus.PENDING);
		assertThat(history.get(0).getActor()).isEqualTo("user:user-1");
	}

	@Test
	void 같은_멱등키로_다시_요청하면_기존_예약을_200으로_돌려주고_재고는_한번만_차감된다() throws Exception {
		String body = body(checkIn, checkIn.plusDays(1), 2);
		String first = createReservation("user-1", "key-1", body)
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		String second = createReservation("user-1", "key-1", body)
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertThat(second).isEqualTo(first);
		assertThat(fixtures.count("reservation")).isEqualTo(1);
		assertThat(fixtures.bookedCount(roomTypeId, checkIn)).isEqualTo(1);
	}

	@Test
	void 같은_멱등키라도_사용자가_다르면_별개의_예약이다() throws Exception {
		createReservation("user-1", "key-1", body(checkIn, checkIn.plusDays(1), 2)).andExpect(status().isCreated());
		createReservation("user-2", "key-1", body(checkIn, checkIn.plusDays(1), 2)).andExpect(status().isCreated());

		assertThat(fixtures.count("reservation")).isEqualTo(2);
	}

	@Test
	void 여러_박_중_하루라도_매진이면_예약이_실패하고_다른_날짜의_재고는_그대로다() throws Exception {
		fixtures.setBooked(roomTypeId, checkIn.plusDays(1), 5); // 3박 중 둘째 날 매진

		createReservation("user-1", "key-1", body(checkIn, checkIn.plusDays(3), 2))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SOLD_OUT"));

		assertThat(fixtures.bookedCount(roomTypeId, checkIn)).isZero();
		assertThat(fixtures.bookedCount(roomTypeId, checkIn.plusDays(2))).isZero();
		assertThat(fixtures.count("reservation")).isZero();
		assertThat(fixtures.count("reservation_history")).isZero();
	}

	@Test
	void 재고_행이_없는_날짜가_포함되면_매진으로_처리한다() throws Exception {
		jdbc.update("DELETE FROM room_inventory WHERE room_type_id = ? AND stay_date = ?", roomTypeId, checkIn.plusDays(1));

		createReservation("user-1", "key-1", body(checkIn, checkIn.plusDays(3), 2))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SOLD_OUT"));

		assertThat(fixtures.bookedCount(roomTypeId, checkIn)).isZero();
	}

	@Test
	void 요금이_없는_날짜가_포함되면_RATE_NOT_FOUND_이고_재고는_차감되지_않는다() throws Exception {
		fixtures.deleteRate(roomTypeId, checkIn.plusDays(1));

		createReservation("user-1", "key-1", body(checkIn, checkIn.plusDays(3), 2))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("RATE_NOT_FOUND"));

		assertThat(fixtures.bookedCount(roomTypeId, checkIn)).isZero();
		assertThat(fixtures.count("reservation")).isZero();
	}

	@Test
	void 체크인이_오늘이면_예약할_수_있고_어제면_거부한다() throws Exception {
		LocalDate today = LocalDate.now(clock);
		long todayRoom = fixtures.roomType(2, 1, 100_000, today.minusDays(1), today.plusDays(1));

		createReservation("user-1", "key-1", body(todayRoom, today, today.plusDays(1), 2))
				.andExpect(status().isCreated());
		createReservation("user-1", "key-2", body(todayRoom, today.minusDays(1), today, 2))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.details.checkIn").exists());
	}

	@Test
	void 체크아웃이_체크인보다_늦지_않으면_거부한다() throws Exception {
		createReservation("user-1", "key-1", body(checkIn, checkIn, 2))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.checkOut").exists());
	}

	@Test
	void 최대_30박을_넘으면_거부한다() throws Exception {
		createReservation("user-1", "key-1", body(checkIn, checkIn.plusDays(31), 2))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.checkOut").exists());
	}

	@Test
	void 인원이_객실_정원을_넘으면_거부하고_0명이면_형식_검증에서_거부한다() throws Exception {
		createReservation("user-1", "key-1", body(checkIn, checkIn.plusDays(1), 3))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.guestCount").exists());
		createReservation("user-1", "key-2", body(checkIn, checkIn.plusDays(1), 0))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.guestCount").exists());

		assertThat(fixtures.bookedCount(roomTypeId, checkIn)).isZero();
	}

	@Test
	void 없는_객실_타입이면_404() throws Exception {
		createReservation("user-1", "key-1", body(9999L, checkIn, checkIn.plusDays(1), 2))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void Idempotency_Key_헤더가_없으면_거부한다() throws Exception {
		mockMvc.perform(post("/api/reservations")
						.header("X-User-Id", "user-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(checkIn, checkIn.plusDays(1), 2)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MISSING_HEADER"));
	}

	@Test
	void 날짜_형식이_잘못되면_400() throws Exception {
		String body = body(checkIn, checkIn.plusDays(1), 2).replace(checkIn.toString(), "2026/12/01");

		createReservation("user-1", "key-1", body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	private ResultActions createReservation(String userId, String key, String body) throws Exception {
		return mockMvc.perform(post("/api/reservations")
				.header("X-User-Id", userId)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private String body(LocalDate in, LocalDate out, int guests) {
		return body(roomTypeId, in, out, guests);
	}

	private static String body(long roomTypeId, LocalDate in, LocalDate out, int guests) {
		return """
				{"roomTypeId": %d, "checkIn": "%s", "checkOut": "%s", "guestCount": %d,
				 "guestName": "홍길동", "guestPhone": "010-0000-0000"}
				""".formatted(roomTypeId, in, out, guests);
	}
}
