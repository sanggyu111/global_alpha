package com.staypoint.mypage;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.staypoint.TestcontainersConfiguration;
import com.staypoint.reservation.ReservationService;
import com.staypoint.reservation.dto.CreateReservationRequest;
import com.staypoint.support.TestFixtures;

/**
 * 내 예약 목록·상세 (FR-UI-2). 결제 확정 상태는 DB 에 직접 만들어 PG 호출 없이 조회만 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MyReservationApiTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ReservationService reservationService;

	@Autowired
	Clock clock;

	TestFixtures fixtures;
	LocalDate today;
	long roomTypeId;

	@BeforeEach
	void setUp() {
		fixtures = new TestFixtures(jdbc);
		fixtures.truncateAll();
		today = LocalDate.now(clock);
		roomTypeId = fixtures.roomType(2, 5, 100_000, today, today.plusDays(20));
	}

	@Test
	void 내_예약_목록은_본인_것만_최근_순으로_숙소와_객실_이름과_함께_보여준다() throws Exception {
		long first = reserve("user-1", today.plusDays(10));
		reserve("user-2", today.plusDays(10));
		long second = reserve("user-1", today.plusDays(12));

		mockMvc.perform(get("/api/reservations/me").header("X-User-Id", "user-1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[*].id", contains((int) second, (int) first)))
				.andExpect(jsonPath("$[0].propertyName").value("테스트 호텔"))
				.andExpect(jsonPath("$[0].roomTypeName").value("스탠다드"))
				.andExpect(jsonPath("$[0].status").value("PENDING"));
	}

	@Test
	void 확정_예약_상세는_지금_취소하면_받을_환불_예정액을_보여준다() throws Exception {
		long id = confirm(reserve("user-1", today.plusDays(5))); // D=5 → 70%

		mockMvc.perform(get("/api/reservations/{id}", id).header("X-User-Id", "user-1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"))
				.andExpect(jsonPath("$.nights").value(1))
				.andExpect(jsonPath("$.payment.status").value("APPROVED"))
				.andExpect(jsonPath("$.refundEstimate.daysBeforeCheckIn").value(5))
				.andExpect(jsonPath("$.refundEstimate.percent").value(70))
				.andExpect(jsonPath("$.refundEstimate.amount").value(70_000))
				.andExpect(jsonPath("$.refundStatus").doesNotExist());
	}

	@Test
	void 결제_전_예약은_결제와_환불_예정이_없고_선점_만료_시각을_보여준다() throws Exception {
		long id = reserve("user-1", today.plusDays(5));

		mockMvc.perform(get("/api/reservations/{id}", id).header("X-User-Id", "user-1"))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.holdExpiresAt").isNotEmpty())
				.andExpect(jsonPath("$.payment").doesNotExist())
				.andExpect(jsonPath("$.refundEstimate").doesNotExist());
	}

	@Test
	void 취소된_예약은_확정된_환불액과_환불_진행_상태를_보여준다() throws Exception {
		long id = confirm(reserve("user-1", today.plusDays(10)));
		jdbc.update("UPDATE reservation SET status = 'CANCELED', cancel_reason = 'USER_CANCEL', canceled_at = now(), "
				+ "refund_amount = 100000 WHERE id = ?", id);
		jdbc.update("INSERT INTO payment_cancel (payment_id, cancel_amount, reason, status, cancel_key) "
				+ "SELECT id, 100000, 'USER_CANCEL', 'PENDING', 'USER-' || id FROM payment WHERE reservation_id = ?", id);

		mockMvc.perform(get("/api/reservations/{id}", id).header("X-User-Id", "user-1"))
				.andExpect(jsonPath("$.status").value("CANCELED"))
				.andExpect(jsonPath("$.refundAmount").value(100_000))
				.andExpect(jsonPath("$.refundStatus").value("PENDING"))
				.andExpect(jsonPath("$.refundEstimate").doesNotExist());
	}

	@Test
	void 다른_사람의_예약은_볼_수_없고_없는_예약은_404() throws Exception {
		long id = reserve("user-1", today.plusDays(5));

		mockMvc.perform(get("/api/reservations/{id}", id).header("X-User-Id", "user-2"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("NOT_OWNER"));
		mockMvc.perform(get("/api/reservations/{id}", 9999).header("X-User-Id", "user-1"))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/reservations/me"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MISSING_HEADER"));
	}

	private long reserve(String userId, LocalDate checkIn) {
		return reservationService.create(userId, "key-" + System.nanoTime(),
				new CreateReservationRequest(roomTypeId, checkIn, checkIn.plusDays(1), 2, "홍길동", "010-0000-0000"))
				.reservation().id();
	}

	/** 결제 승인 · 예약 확정 상태를 DB 에 직접 만든다. */
	private long confirm(long reservationId) {
		jdbc.update("UPDATE reservation SET status = 'CONFIRMED', confirmed_at = now() WHERE id = ?", reservationId);
		jdbc.update("INSERT INTO payment (reservation_id, pg_order_id, pg_tid, amount, status, idempotency_key, approved_at) "
				+ "VALUES (?, ?, ?, 100000, 'APPROVED', ?, now())",
				reservationId, "P-" + reservationId, "T-" + reservationId, "pay-" + reservationId);
		return reservationId;
	}
}
