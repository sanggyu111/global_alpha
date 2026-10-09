package com.staypoint.support;

import java.time.LocalDate;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 통합 테스트용 데이터 준비. 시드와 무관하게 테스트마다 필요한 숙소·객실·재고·요금을 직접 만든다.
 */
public class TestFixtures {

	private final JdbcTemplate jdbc;

	public TestFixtures(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void truncateAll() {
		jdbc.execute("TRUNCATE property, room_type, room_inventory, room_rate, reservation, "
				+ "reservation_history, payment, payment_cancel RESTART IDENTITY CASCADE");
	}

	/** 정원 capacity 인 객실 타입을 만들고, from ~ to(미포함) 날짜마다 재고 total 실과 1박 price 원을 등록한다. */
	public long roomType(int capacity, int total, int price, LocalDate from, LocalDate to) {
		long propertyId = jdbc.queryForObject(
				"INSERT INTO property (name, address, region) VALUES ('테스트 호텔', '서울', '서울') RETURNING id", Long.class);
		long roomTypeId = jdbc.queryForObject(
				"INSERT INTO room_type (property_id, name, capacity, default_total_rooms) VALUES (?, '스탠다드', ?, ?) RETURNING id",
				Long.class, propertyId, capacity, total);
		for (LocalDate d = from; d.isBefore(to); d = d.plusDays(1)) {
			jdbc.update("INSERT INTO room_inventory (room_type_id, stay_date, total_count) VALUES (?, ?, ?)", roomTypeId, d, total);
			jdbc.update("INSERT INTO room_rate (room_type_id, stay_date, price) VALUES (?, ?, ?)", roomTypeId, d, price);
		}
		return roomTypeId;
	}

	public int bookedCount(long roomTypeId, LocalDate stayDate) {
		return jdbc.queryForObject("SELECT booked_count FROM room_inventory WHERE room_type_id = ? AND stay_date = ?",
				Integer.class, roomTypeId, stayDate);
	}

	public void setBooked(long roomTypeId, LocalDate stayDate, int booked) {
		jdbc.update("UPDATE room_inventory SET booked_count = ? WHERE room_type_id = ? AND stay_date = ?",
				booked, roomTypeId, stayDate);
	}

	public void setPrice(long roomTypeId, LocalDate stayDate, int price) {
		jdbc.update("UPDATE room_rate SET price = ? WHERE room_type_id = ? AND stay_date = ?", price, roomTypeId, stayDate);
	}

	public void deleteRate(long roomTypeId, LocalDate stayDate) {
		jdbc.update("DELETE FROM room_rate WHERE room_type_id = ? AND stay_date = ?", roomTypeId, stayDate);
	}

	public int count(String table) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
	}
}
