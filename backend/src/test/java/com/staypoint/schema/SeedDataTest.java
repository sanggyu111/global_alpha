package com.staypoint.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.staypoint.TestcontainersConfiguration;

/**
 * seed 프로파일에서 빈 DB 에 데모 데이터가 준비되는지 검증한다 (NFR-7 재현성).
 */
@SpringBootTest
@ActiveProfiles("seed")
@Import(TestcontainersConfiguration.class)
class SeedDataTest {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void 숙소_3개와_객실타입_6개가_만들어진다() {
		assertThat(count("property")).isEqualTo(3);
		assertThat(count("room_type")).isEqualTo(6);
	}

	@Test
	void 객실타입마다_오늘부터_90일치_재고와_요금이_있다() {
		assertThat(count("room_inventory")).isEqualTo(6 * 90);
		assertThat(count("room_rate")).isEqualTo(6 * 90);
		Integer missingToday = jdbc.queryForObject("""
				SELECT COUNT(*) FROM room_type rt
				WHERE NOT EXISTS (SELECT 1 FROM room_inventory i WHERE i.room_type_id = rt.id AND i.stay_date = current_date)
				""", Integer.class);
		assertThat(missingToday).isZero();
	}

	@Test
	void 금요일과_토요일_밤은_30퍼센트_할증된다() {
		BigDecimal weekday = rateOn("스탠다드 더블", 1); // 월요일
		BigDecimal friday = rateOn("스탠다드 더블", 5);
		BigDecimal saturday = rateOn("스탠다드 더블", 6);

		assertThat(weekday).isEqualByComparingTo("120000");
		assertThat(friday).isEqualByComparingTo("156000");
		assertThat(saturday).isEqualByComparingTo("156000");
	}

	@Test
	void 마지막_1실_데모용_객실타입은_재고가_1이다() {
		Integer total = jdbc.queryForObject("""
				SELECT DISTINCT i.total_count FROM room_inventory i JOIN room_type rt ON rt.id = i.room_type_id
				WHERE rt.name = '독채 풀빌라'
				""", Integer.class);
		assertThat(total).isEqualTo(1);
	}

	private int count(String table) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
	}

	private BigDecimal rateOn(String roomName, int isoDayOfWeek) {
		return jdbc.queryForObject("""
				SELECT r.price FROM room_rate r JOIN room_type rt ON rt.id = r.room_type_id
				WHERE rt.name = ? AND EXTRACT(ISODOW FROM r.stay_date) = ?
				LIMIT 1
				""", BigDecimal.class, roomName, isoDayOfWeek);
	}
}
