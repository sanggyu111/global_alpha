package com.staypoint.property;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 가용 객실 검색 쿼리 (FR-SRCH-2·3). 여러 테이블을 모아 숫자를 계산하는 조회라 엔티티 대신 SQL 로 직접 쓴다.
 */
@Repository
class AvailabilityRepository {

	private final JdbcTemplate jdbc;

	AvailabilityRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	record AvailableRoomType(long roomTypeId, String name, int capacity, int remaining, BigDecimal totalPrice) {
	}

	/**
	 * 숙박 기간 "모든 날짜" 에 잔여 재고 ≥ 1 이고 요금이 있으며 정원 ≥ 인원인 객실 타입.
	 * <ul>
	 *   <li>재고와 요금을 같은 날짜로 JOIN → 둘 중 하나라도 없는 날짜는 행이 빠진다</li>
	 *   <li>HAVING COUNT(*) = 숙박일 수 → 빠진 날짜가 있으면 그 객실 타입은 제외</li>
	 *   <li>HAVING MIN(잔여) ≥ 1 → 하루라도 매진이면 제외. remaining 은 기간 중 가장 적은 날의 잔여</li>
	 * </ul>
	 * 체크아웃 날짜는 재고를 쓰지 않으므로 포함하지 않는다 (stay_date &lt; checkOut).
	 * 이 결과는 "참고값" 이다 — 최종 판단은 예약 생성 시 조건부 UPDATE 가 한다.
	 */
	List<AvailableRoomType> findAvailable(long propertyId, LocalDate checkIn, LocalDate checkOut, long nights,
			int guests) {
		return jdbc.query("""
				SELECT rt.id, rt.name, rt.capacity,
				       MIN(i.total_count - i.booked_count) AS remaining,
				       SUM(r.price)                        AS total_price
				  FROM room_type rt
				  JOIN room_inventory i ON i.room_type_id = rt.id
				                       AND i.stay_date >= ? AND i.stay_date < ?
				  JOIN room_rate r      ON r.room_type_id = rt.id AND r.stay_date = i.stay_date
				 WHERE rt.property_id = ? AND rt.capacity >= ?
				 GROUP BY rt.id, rt.name, rt.capacity
				HAVING COUNT(*) = ? AND MIN(i.total_count - i.booked_count) >= 1
				 ORDER BY total_price, rt.id
				""",
				(rs, i) -> new AvailableRoomType(rs.getLong("id"), rs.getString("name"), rs.getInt("capacity"),
						rs.getInt("remaining"), rs.getBigDecimal("total_price")),
				checkIn, checkOut, propertyId, guests, nights);
	}
}
