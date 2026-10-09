package com.staypoint.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.staypoint.TestcontainersConfiguration;

/**
 * Flyway V1 이 만든 DB 제약이 "애플리케이션 코드가 틀려도" 정합성을 지키는지 검증한다.
 * 설계 3.2 / 4.1 의 최종 방어선들이 실제 PostgreSQL 에서 동작하는지 확인하는 테스트.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaConstraintTest {

	@Autowired
	JdbcTemplate jdbc;

	long roomTypeId;

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE property, room_type, room_inventory, room_rate, reservation, "
				+ "reservation_history, payment, payment_cancel RESTART IDENTITY CASCADE");
		long propertyId = jdbc.queryForObject(
				"INSERT INTO property (name, address, region) VALUES ('테스트 호텔', '서울', '서울') RETURNING id",
				Long.class);
		roomTypeId = jdbc.queryForObject(
				"INSERT INTO room_type (property_id, name, capacity, default_total_rooms) VALUES (?, '스탠다드', 2, 1) RETURNING id",
				Long.class, propertyId);
	}

	@Test
	void 재고_CHECK_제약은_booked_count_가_total_count_를_넘지_못하게_한다() {
		jdbc.update("INSERT INTO room_inventory (room_type_id, stay_date, total_count, booked_count) VALUES (?, DATE '2026-12-01', 1, 1)",
				roomTypeId);

		// 조건 없이 무작정 +1 하는 "버그 코드" 를 흉내 내도 DB 가 거부해야 한다
		assertThatThrownBy(() -> jdbc.update(
				"UPDATE room_inventory SET booked_count = booked_count + 1 WHERE room_type_id = ?", roomTypeId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_room_inventory_booked");
	}

	@Test
	void 재고_CHECK_제약은_booked_count_가_음수가_되지_못하게_한다() {
		jdbc.update("INSERT INTO room_inventory (room_type_id, stay_date, total_count, booked_count) VALUES (?, DATE '2026-12-01', 1, 0)",
				roomTypeId);

		// 취소 시 재고 복원을 두 번 하는 버그를 흉내
		assertThatThrownBy(() -> jdbc.update(
				"UPDATE room_inventory SET booked_count = booked_count - 1 WHERE room_type_id = ?", roomTypeId))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 같은_객실_같은_날짜의_재고_행은_하나만_존재한다() {
		jdbc.update("INSERT INTO room_inventory (room_type_id, stay_date, total_count) VALUES (?, DATE '2026-12-01', 1)", roomTypeId);

		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO room_inventory (room_type_id, stay_date, total_count) VALUES (?, DATE '2026-12-01', 5)", roomTypeId))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 같은_사용자의_같은_멱등키로는_예약이_하나만_생긴다() {
		insertReservation("user-1", "key-1", "R-001");

		assertThatThrownBy(() -> insertReservation("user-1", "key-1", "R-002"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uq_reservation_idempotency");
	}

	@Test
	void 한_예약에_진행중_또는_승인된_결제는_하나뿐이고_실패한_결제_뒤에는_재결제할_수_있다() {
		long reservationId = insertReservation("user-1", "key-1", "R-001");
		jdbc.update("INSERT INTO payment (reservation_id, pg_order_id, amount, status, idempotency_key) VALUES (?, 'O-1', 100000, 'READY', 'pay-1')",
				reservationId);

		// 다른 멱등키로 동시에 결제를 시도해도 부분 UNIQUE 인덱스가 막는다
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO payment (reservation_id, pg_order_id, amount, status, idempotency_key) VALUES (?, 'O-2', 100000, 'READY', 'pay-2')",
				reservationId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uq_payment_active_per_reservation");

		// 첫 결제가 실패하면 새 결제는 허용된다
		jdbc.update("UPDATE payment SET status = 'FAILED' WHERE pg_order_id = 'O-1'");
		int inserted = jdbc.update(
				"INSERT INTO payment (reservation_id, pg_order_id, amount, status, idempotency_key) VALUES (?, 'O-3', 100000, 'READY', 'pay-3')",
				reservationId);
		assertThat(inserted).isEqualTo(1);
	}

	@Test
	void 체크아웃은_체크인보다_뒤여야_한다() {
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO reservation (reservation_no, user_id, room_type_id, check_in, check_out, guest_count,
				    guest_name, guest_phone, total_amount, status, hold_expires_at, idempotency_key)
				VALUES ('R-X', 'user-1', ?, DATE '2026-12-02', DATE '2026-12-02', 2, '홍길동', '010', 0, 'PENDING', now(), 'k')
				""", roomTypeId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_reservation_dates");
	}

	private long insertReservation(String userId, String idempotencyKey, String reservationNo) {
		return jdbc.queryForObject("""
				INSERT INTO reservation (reservation_no, user_id, room_type_id, check_in, check_out, guest_count,
				    guest_name, guest_phone, total_amount, status, hold_expires_at, idempotency_key)
				VALUES (?, ?, ?, DATE '2026-12-01', DATE '2026-12-02', 2, '홍길동', '010-0000-0000', 100000, 'PENDING', now(), ?)
				RETURNING id
				""", Long.class, reservationNo, userId, roomTypeId, idempotencyKey);
	}
}
