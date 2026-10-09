package com.staypoint.property;

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
import org.springframework.test.web.servlet.ResultActions;

import com.staypoint.TestcontainersConfiguration;
import com.staypoint.reservation.ReservationService;
import com.staypoint.reservation.dto.CreateReservationRequest;
import com.staypoint.support.TestFixtures;

/**
 * 숙소 목록·상세와 가용 객실 검색 (FR-SRCH-1~4).
 * 가용 = 숙박 기간 모든 날짜에 잔여 재고 ≥ 1, 요금 등록, 정원 ≥ 인원.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PropertyApiTest {

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
	long propertyId;
	long standard; // 정원 2, 재고 3, 1박 100,000
	long family;   // 정원 4, 재고 1, 1박 200,000

	@BeforeEach
	void setUp() {
		fixtures = new TestFixtures(jdbc);
		fixtures.truncateAll();
		in = LocalDate.now(clock).plusDays(10);
		standard = fixtures.roomType(2, 3, 100_000, in, in.plusDays(5));
		propertyId = jdbc.queryForObject("SELECT property_id FROM room_type WHERE id = ?", Long.class, standard);
		family = jdbc.queryForObject("""
				INSERT INTO room_type (property_id, name, capacity, default_total_rooms) VALUES (?, '패밀리', 4, 1) RETURNING id
				""", Long.class, propertyId);
		for (int d = 0; d < 5; d++) {
			jdbc.update("INSERT INTO room_inventory (room_type_id, stay_date, total_count) VALUES (?, ?, 1)", family, in.plusDays(d));
			jdbc.update("INSERT INTO room_rate (room_type_id, stay_date, price) VALUES (?, ?, 200000)", family, in.plusDays(d));
		}
	}

	@Test
	void 숙소_목록은_지역으로_거를_수_있다() throws Exception {
		jdbc.update("INSERT INTO property (name, address, region) VALUES ('부산 호텔', '부산 해운대구', '부산')");

		mockMvc.perform(get("/api/properties")).andExpect(jsonPath("$", hasSize(2)));
		mockMvc.perform(get("/api/properties").param("region", "부산"))
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].name").value("부산 호텔"));
	}

	@Test
	void 숙소_상세는_객실_타입_목록을_포함하고_없는_숙소는_404() throws Exception {
		mockMvc.perform(get("/api/properties/{id}", propertyId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.roomTypes[*].name", contains("스탠다드", "패밀리")))
				.andExpect(jsonPath("$.roomTypes[1].capacity").value(4));

		mockMvc.perform(get("/api/properties/{id}", 9999)).andExpect(status().isNotFound());
	}

	@Test
	void 날짜별_요금과_총액을_보여주고_총액이_싼_순서로_정렬한다() throws Exception {
		fixtures.setPrice(standard, in.plusDays(1), 130_000); // 둘째 날만 주말 요금

		search(in, in.plusDays(3), 2)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nights").value(3))
				.andExpect(jsonPath("$.rooms", hasSize(2)))
				.andExpect(jsonPath("$.rooms[0].name").value("스탠다드"))
				.andExpect(jsonPath("$.rooms[0].nightlyRates", hasSize(3)))
				.andExpect(jsonPath("$.rooms[0].nightlyRates[1].price").value(130_000))
				.andExpect(jsonPath("$.rooms[0].totalPrice").value(330_000))
				.andExpect(jsonPath("$.rooms[0].remaining").value(3))
				.andExpect(jsonPath("$.rooms[1].totalPrice").value(600_000));
	}

	@Test
	void 여러_박_중_하루라도_매진이면_그_객실은_나오지_않는다() throws Exception {
		fixtures.setBooked(family, in.plusDays(1), 1);

		search(in, in.plusDays(3), 2)
				.andExpect(jsonPath("$.rooms[*].name", contains("스탠다드")));
	}

	@Test
	void 잔여_객실_수는_기간_중_가장_적게_남은_날_기준이다() throws Exception {
		fixtures.setBooked(standard, in.plusDays(2), 2);

		search(in, in.plusDays(3), 2)
				.andExpect(jsonPath("$.rooms[0].remaining").value(1));
	}

	@Test
	void 체크아웃_날짜가_매진이어도_숙박에는_영향이_없다() throws Exception {
		fixtures.setBooked(family, in.plusDays(2), 1); // 2박 → 체크아웃 날짜

		search(in, in.plusDays(2), 2)
				.andExpect(jsonPath("$.rooms[*].name", contains("스탠다드", "패밀리")));
	}

	@Test
	void 인원이_정원보다_많으면_그_객실은_나오지_않는다() throws Exception {
		search(in, in.plusDays(1), 3)
				.andExpect(jsonPath("$.rooms[*].name", contains("패밀리")));
		search(in, in.plusDays(1), 5)
				.andExpect(jsonPath("$.rooms", hasSize(0)));
	}

	@Test
	void 재고나_요금이_등록되지_않은_날짜가_있으면_나오지_않는다() throws Exception {
		fixtures.deleteRate(standard, in.plusDays(1));
		jdbc.update("DELETE FROM room_inventory WHERE room_type_id = ? AND stay_date = ?", family, in.plusDays(2));

		search(in, in.plusDays(3), 2).andExpect(jsonPath("$.rooms", hasSize(0)));
		search(in.plusDays(5), in.plusDays(6), 2).andExpect(jsonPath("$.rooms", hasSize(0))); // 등록 기간 밖
	}

	@Test
	void 예약이_생기면_잔여가_줄고_마지막_객실이_예약되면_사라진다() throws Exception {
		reserve(family, in, in.plusDays(2));

		search(in, in.plusDays(2), 2)
				.andExpect(jsonPath("$.rooms[*].name", contains("스탠다드")));
		reserve(standard, in, in.plusDays(1));
		search(in, in.plusDays(1), 2)
				.andExpect(jsonPath("$.rooms[0].remaining").value(2));
	}

	@Test
	void 잘못된_검색_조건은_400() throws Exception {
		LocalDate today = LocalDate.now(clock);
		search(in, in, 2).andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.checkOut").exists());
		search(today.minusDays(1), today.plusDays(1), 2).andExpect(jsonPath("$.details.checkIn").exists());
		search(in, in.plusDays(31), 2).andExpect(jsonPath("$.details.checkOut").exists());
		search(in, in.plusDays(1), 0).andExpect(jsonPath("$.details.guests").exists());
		mockMvc.perform(get("/api/properties/{id}/availability", propertyId).param("checkIn", "2026/12/01")
				.param("checkOut", in.toString()).param("guests", "2"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/properties/{id}/availability", propertyId))
				.andExpect(status().isBadRequest());
	}

	@Test
	void 없는_숙소의_가용_검색은_404() throws Exception {
		mockMvc.perform(get("/api/properties/{id}/availability", 9999)
						.param("checkIn", in.toString()).param("checkOut", in.plusDays(1).toString()).param("guests", "2"))
				.andExpect(status().isNotFound());
	}

	private ResultActions search(LocalDate checkIn, LocalDate checkOut, int guests) throws Exception {
		return mockMvc.perform(get("/api/properties/{id}/availability", propertyId)
				.param("checkIn", checkIn.toString())
				.param("checkOut", checkOut.toString())
				.param("guests", String.valueOf(guests)));
	}

	private void reserve(long roomTypeId, LocalDate checkIn, LocalDate checkOut) {
		reservationService.create("user-" + System.nanoTime(), "key-" + System.nanoTime(),
				new CreateReservationRequest(roomTypeId, checkIn, checkOut, 2, "홍길동", "010-0000-0000"));
	}
}
