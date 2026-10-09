package com.staypoint.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;

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
import com.staypoint.reservation.ReservationService;
import com.staypoint.reservation.dto.CreateReservationRequest;
import com.staypoint.support.TestFixtures;

/**
 * 관리자 API (FR-UI-3~5, 정책 5.5, 설계 6장): 예약 목록 필터·페이지, 재고·요금 기간 설정(S11),
 * 운영자 확인 대상, 재고 불일치 감지·재계산.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminApiTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ReservationService reservationService;

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
		roomTypeId = fixtures.roomType(2, 3, 100_000, in, in.plusDays(5)); // 재고 3, 5일치
	}

	// ---------------------------------------------------------------- 예약 목록

	@Test
	void 예약_목록은_상태_숙소_체크인_날짜로_거르고_최근_순으로_페이지를_나눈다() throws Exception {
		long a = reserve(roomTypeId, in);
		long b = reserve(roomTypeId, in.plusDays(1));
		long c = reserve(roomTypeId, in.plusDays(2));
		jdbc.update("UPDATE reservation SET status = 'CONFIRMED' WHERE id = ?", b);
		long otherRoom = fixtures.roomType(2, 3, 100_000, in, in.plusDays(1)); // 다른 숙소
		reserve(otherRoom, in);

		mockMvc.perform(get("/api/admin/reservations"))
				.andExpect(jsonPath("$.totalElements").value(4));
		mockMvc.perform(get("/api/admin/reservations").param("status", "PENDING")
						.param("propertyId", String.valueOf(propertyOf(roomTypeId))))
				.andExpect(jsonPath("$.content[*].id", contains((int) c, (int) a)));
		mockMvc.perform(get("/api/admin/reservations").param("from", in.plusDays(1).toString())
						.param("to", in.plusDays(2).toString()))
				.andExpect(jsonPath("$.content[*].id", contains((int) c, (int) b)));
		mockMvc.perform(get("/api/admin/reservations").param("size", "3").param("page", "1"))
				.andExpect(jsonPath("$.content", hasSize(1)))
				.andExpect(jsonPath("$.totalPages").value(2))
				.andExpect(jsonPath("$.content[0].id").value((int) a))
				.andExpect(jsonPath("$.content[0].propertyName").value("테스트 호텔"));
	}

	@Test
	void 잘못된_상태_필터나_페이지_크기는_400() throws Exception {
		mockMvc.perform(get("/api/admin/reservations").param("status", "WHATEVER"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/admin/reservations").param("size", "101"))
				.andExpect(status().isBadRequest());
	}

	// ---------------------------------------------------------------- 재고 · 요금

	@Test
	void 달력은_날짜별_재고_예약_잔여_요금을_보여주고_미등록_날짜는_비어_있다() throws Exception {
		reserve(roomTypeId, in);

		mockMvc.perform(get("/api/admin/room-types/{id}/calendar", roomTypeId)
						.param("from", in.toString()).param("to", in.plusDays(5).toString()))
				.andExpect(jsonPath("$", hasSize(6)))
				.andExpect(jsonPath("$[0].totalCount").value(3))
				.andExpect(jsonPath("$[0].bookedCount").value(1))
				.andExpect(jsonPath("$[0].available").value(2))
				.andExpect(jsonPath("$[0].price").value(100_000))
				.andExpect(jsonPath("$[5].totalCount").value(nullValue()));
	}

	@Test
	void 기간_재고_설정은_없는_날짜를_만들고_있는_날짜는_바꾼다() throws Exception {
		setInventory(in.plusDays(3), in.plusDays(7), 8)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(5)))
				.andExpect(jsonPath("$[*].totalCount", contains(8, 8, 8, 8, 8)));

		assertThat(jdbc.queryForObject("SELECT total_count FROM room_inventory WHERE room_type_id = ? AND stay_date = ?",
				Integer.class, roomTypeId, in.plusDays(2))).isEqualTo(3); // 범위 밖은 그대로
	}

	@Test
	void 예약된_수보다_재고를_줄이면_전체를_거부하고_아무것도_바꾸지_않는다() throws Exception {
		reserve(roomTypeId, in.plusDays(1));
		reserve(roomTypeId, in.plusDays(1));

		setInventory(in, in.plusDays(4), 1)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVENTORY_BELOW_BOOKED"))
				.andExpect(jsonPath("$.details.dates", contains(in.plusDays(1).toString())));

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM room_inventory WHERE room_type_id = ? AND total_count = 3",
				Integer.class, roomTypeId)).isEqualTo(5);
	}

	@Test
	void 예약된_수와_같게_줄이는_것은_허용되고_이후_그_날짜는_매진된다() throws Exception {
		reserve(roomTypeId, in);

		setInventory(in, in, 1).andExpect(status().isOk());

		assertThat(fixtures.bookedCount(roomTypeId, in)).isEqualTo(1);
		assertThatThrownBy(() -> reserve(roomTypeId, in))
				.hasMessageContaining("남은 객실이 없습니다");
	}

	@Test
	void 요금을_바꿔도_이미_만든_예약의_금액은_바뀌지_않는다() throws Exception {
		long id = reserve(roomTypeId, in);

		mockMvc.perform(put("/api/admin/room-types/{id}/rates", roomTypeId).contentType(MediaType.APPLICATION_JSON)
						.content(body(in, in.plusDays(1), "\"price\": 150000")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].price", contains(150_000, 150_000)));

		assertThat(jdbc.queryForObject("SELECT total_amount FROM reservation WHERE id = ?", BigDecimal.class, id))
				.isEqualByComparingTo("100000");
	}

	@Test
	void 잘못된_기간이나_값은_400_없는_객실_타입은_404() throws Exception {
		setInventory(in.plusDays(2), in, 5).andExpect(status().isBadRequest());
		setInventory(in, in.plusDays(366), 5).andExpect(status().isBadRequest());
		setInventory(in, in, -1).andExpect(status().isBadRequest());
		mockMvc.perform(put("/api/admin/room-types/{id}/inventory", 9999).contentType(MediaType.APPLICATION_JSON)
						.content(body(in, in, "\"totalCount\": 1")))
				.andExpect(status().isNotFound());
	}

	// ---------------------------------------------------------------- 운영자 확인 대상

	@Test
	void 운영자_확인_대상은_기본으로_MANUAL_REVIEW_만_보여준다() throws Exception {
		long id = reserve(roomTypeId, in);
		jdbc.update("INSERT INTO payment (reservation_id, pg_order_id, pg_tid, amount, status, idempotency_key) "
				+ "VALUES (?, 'P-1', 'T-1', 100000, 'APPROVED', 'pay-1')", id);
		jdbc.update("INSERT INTO payment_cancel (payment_id, cancel_amount, reason, status, cancel_key, attempt_count, last_error) "
				+ "SELECT id, 100000, 'COMPENSATION', 'MANUAL_REVIEW', 'COMP-1', 5, 'PG 응답 없음' FROM payment");
		jdbc.update("INSERT INTO payment_cancel (payment_id, cancel_amount, reason, status, cancel_key) "
				+ "SELECT id, 1000, 'USER_CANCEL', 'PENDING', 'USER-1' FROM payment");

		mockMvc.perform(get("/api/admin/payment-cancels"))
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].reason").value("COMPENSATION"))
				.andExpect(jsonPath("$[0].attemptCount").value(5))
				.andExpect(jsonPath("$[0].lastError").value("PG 응답 없음"))
				.andExpect(jsonPath("$[0].pgTid").value("T-1"))
				.andExpect(jsonPath("$[0].reservationId").value((int) id));
		mockMvc.perform(get("/api/admin/payment-cancels").param("status", "PENDING"))
				.andExpect(jsonPath("$[0].reason").value("USER_CANCEL"));
		mockMvc.perform(get("/api/admin/payment-cancels").param("status", "NOPE"))
				.andExpect(status().isBadRequest());
	}

	// ---------------------------------------------------------------- 재고 정합성

	@Test
	void 정상_데이터는_불일치가_없고_만료_시각이_지난_PENDING_도_정상으로_본다() throws Exception {
		long a = reserve(roomTypeId, in);
		reserve(roomTypeId, in);
		jdbc.update("UPDATE reservation SET hold_expires_at = now() - interval '1 minute' WHERE id = ?", a); // 스케줄러 처리 전

		mismatches().andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void booked_count_가_어긋난_날짜를_찾아내고_재계산으로_복구한다() throws Exception {
		reserve(roomTypeId, in);
		long canceled = reserve(roomTypeId, in.plusDays(1));
		jdbc.update("UPDATE reservation SET status = 'CANCELED' WHERE id = ?", canceled); // 재고 복원 없이 상태만 바뀐 버그
		fixtures.setBooked(roomTypeId, in.plusDays(3), 2);                                   // 수동 SQL 로 잘못 올린 경우

		mismatches()
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[0].stayDate").value(in.plusDays(1).toString()))
				.andExpect(jsonPath("$[0].bookedCount").value(1))
				.andExpect(jsonPath("$[0].expectedBookedCount").value(0))
				.andExpect(jsonPath("$[1].stayDate").value(in.plusDays(3).toString()));

		mockMvc.perform(post("/api/admin/room-types/{id}/inventory/{date}/recount", roomTypeId, in.plusDays(1)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.before").value(1))
				.andExpect(jsonPath("$.after").value(0));
		mockMvc.perform(post("/api/admin/room-types/{id}/inventory/{date}/recount", roomTypeId, in.plusDays(3)));

		mismatches().andExpect(jsonPath("$", hasSize(0)));
		assertThat(fixtures.bookedCount(roomTypeId, in)).isEqualTo(1); // 정상 날짜는 그대로
	}

	@Test
	void 재고가_없는_날짜의_재계산은_404_활성_예약이_재고보다_많으면_보정하지_않는다() throws Exception {
		mockMvc.perform(post("/api/admin/room-types/{id}/inventory/{date}/recount", roomTypeId, in.plusDays(30)))
				.andExpect(status().isNotFound());

		reserve(roomTypeId, in);
		reserve(roomTypeId, in);
		jdbc.update("UPDATE room_inventory SET booked_count = 0, total_count = 1 WHERE room_type_id = ? AND stay_date = ?",
				roomTypeId, in); // 예약 2건인데 재고 1 — 보정으로 해결할 수 없는 상태

		mockMvc.perform(post("/api/admin/room-types/{id}/inventory/{date}/recount", roomTypeId, in))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVENTORY_BELOW_BOOKED"));
	}

	// ---------------------------------------------------------------- helpers

	private long reserve(long roomType, LocalDate checkIn) {
		return reservationService.create("user-" + System.nanoTime(), "key-" + System.nanoTime(),
				new CreateReservationRequest(roomType, checkIn, checkIn.plusDays(1), 2, "홍길동", "010-0000-0000"))
				.reservation().id();
	}

	private long propertyOf(long roomType) {
		return jdbc.queryForObject("SELECT property_id FROM room_type WHERE id = ?", Long.class, roomType);
	}

	private ResultActions setInventory(LocalDate from, LocalDate to, int total) throws Exception {
		return mockMvc.perform(put("/api/admin/room-types/{id}/inventory", roomTypeId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(from, to, "\"totalCount\": " + total)));
	}

	private ResultActions mismatches() throws Exception {
		return mockMvc.perform(get("/api/admin/inventory/mismatches")
				.param("from", in.toString()).param("to", in.plusDays(10).toString()));
	}

	private static String body(LocalDate from, LocalDate to, String field) {
		return "{\"from\": \"%s\", \"to\": \"%s\", %s}".formatted(from, to, field);
	}
}
