package com.staypoint.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.staypoint.admin.AdminDtos.CalendarDay;
import com.staypoint.admin.AdminDtos.InventoryMismatch;
import com.staypoint.admin.AdminDtos.RecountResult;
import com.staypoint.common.error.BusinessException;
import com.staypoint.common.error.ErrorCode;
import com.staypoint.property.RoomTypeRepository;

/**
 * 관리자 재고·요금 관리 (FR-UI-4, 정책 5.5)와 재고 정합성 점검·복구 (설계 6장, README Q6).
 */
@Service
public class AdminInventoryService {

	private static final Logger log = LoggerFactory.getLogger(AdminInventoryService.class);

	/** 날짜를 쓰는 활성 예약 = 재고를 차지하고 있어야 하는 예약. 만료 시각이 지난 PENDING 도 스케줄러 처리 전까지는 포함. */
	private static final String ACTIVE_STATUSES = "('PENDING', 'CONFIRMED', 'COMPLETED')";

	private final JdbcTemplate jdbc;
	private final RoomTypeRepository roomTypeRepository;

	public AdminInventoryService(JdbcTemplate jdbc, RoomTypeRepository roomTypeRepository) {
		this.jdbc = jdbc;
		this.roomTypeRepository = roomTypeRepository;
	}

	@Transactional(readOnly = true)
	public List<CalendarDay> calendar(Long roomTypeId, LocalDate from, LocalDate to) {
		requireRoomType(roomTypeId);
		AdminRanges.validate(from, to);
		return jdbc.query("""
				SELECT d::date AS stay_date, i.total_count, i.booked_count, r.price
				  FROM generate_series(?::date, ?::date, interval '1 day') d
				  LEFT JOIN room_inventory i ON i.room_type_id = ? AND i.stay_date = d::date
				  LEFT JOIN room_rate r      ON r.room_type_id = ? AND r.stay_date = d::date
				 ORDER BY 1
				""",
				(rs, i) -> {
					Integer total = (Integer) rs.getObject("total_count");
					Integer booked = (Integer) rs.getObject("booked_count");
					return new CalendarDay(rs.getObject("stay_date", LocalDate.class), total, booked,
							total == null ? null : total - booked, rs.getBigDecimal("price"));
				},
				from, to, roomTypeId, roomTypeId);
	}

	/**
	 * 기간 재고 일괄 설정. 없는 날짜는 새로 만들고 있는 날짜는 total_count 만 바꾼다.
	 * 이미 예약된 수보다 줄이려는 날짜가 하나라도 있으면 전체를 거부한다 (S11).
	 *
	 * <p>먼저 기간의 재고 행을 날짜 순으로 잠근다 (FOR UPDATE) → 검사와 변경 사이에 예약이 끼어들어
	 * booked_count 를 올리지 못한다 (예약 쪽 조건부 UPDATE 는 잠금을 기다렸다가 새 total 로 다시 평가).
	 * 최종 방어선은 CHECK (booked_count <= total_count).
	 */
	@Transactional
	public List<CalendarDay> setInventory(Long roomTypeId, LocalDate from, LocalDate to, int totalCount) {
		requireRoomType(roomTypeId);
		AdminRanges.validate(from, to);
		List<LocalDate> belowBooked = jdbc.query("""
				SELECT stay_date, booked_count FROM room_inventory
				 WHERE room_type_id = ? AND stay_date BETWEEN ? AND ?
				 ORDER BY stay_date
				   FOR UPDATE
				""",
				(rs, i) -> rs.getInt("booked_count") > totalCount ? rs.getObject("stay_date", LocalDate.class) : null,
				roomTypeId, from, to)
				.stream().filter(Objects::nonNull).toList();
		if (!belowBooked.isEmpty()) {
			throw new BusinessException(ErrorCode.INVENTORY_BELOW_BOOKED, ErrorCode.INVENTORY_BELOW_BOOKED.defaultMessage(),
					Map.of("dates", belowBooked.stream().map(LocalDate::toString).toList(), "requested", totalCount));
		}
		jdbc.update("""
				INSERT INTO room_inventory (room_type_id, stay_date, total_count)
				SELECT ?, d::date, ? FROM generate_series(?::date, ?::date, interval '1 day') d
				ON CONFLICT (room_type_id, stay_date)
				DO UPDATE SET total_count = EXCLUDED.total_count, updated_at = now()
				""", roomTypeId, totalCount, from, to);
		return calendar(roomTypeId, from, to);
	}

	/** 기간 요금 일괄 설정. 이미 만든 예약의 금액은 생성 시점에 저장돼 있어 바뀌지 않는다 (정책 5.5). */
	@Transactional
	public List<CalendarDay> setRates(Long roomTypeId, LocalDate from, LocalDate to, BigDecimal price) {
		requireRoomType(roomTypeId);
		AdminRanges.validate(from, to);
		jdbc.update("""
				INSERT INTO room_rate (room_type_id, stay_date, price)
				SELECT ?, d::date, ? FROM generate_series(?::date, ?::date, interval '1 day') d
				ON CONFLICT (room_type_id, stay_date) DO UPDATE SET price = EXCLUDED.price
				""", roomTypeId, price, from, to);
		return calendar(roomTypeId, from, to);
	}

	/**
	 * 재고 불일치 감지: booked_count 와 "그 날짜를 쓰는 활성 예약 수" 가 다른 날짜.
	 * 정답은 예약 테이블 — 각 예약이 어떤 날짜를 쓰는지 근거가 남아 있다. booked_count 는 빠른 검사용 파생값.
	 */
	@Transactional(readOnly = true)
	public List<InventoryMismatch> mismatches(LocalDate from, LocalDate to) {
		AdminRanges.validate(from, to);
		return jdbc.query("""
				SELECT i.room_type_id, i.stay_date, i.total_count, i.booked_count, COUNT(r.id) AS expected
				  FROM room_inventory i
				  LEFT JOIN reservation r
				    ON r.room_type_id = i.room_type_id
				   AND i.stay_date >= r.check_in AND i.stay_date < r.check_out
				   AND r.status IN """ + ACTIVE_STATUSES + """

				 WHERE i.stay_date BETWEEN ? AND ?
				 GROUP BY i.id
				HAVING i.booked_count <> COUNT(r.id)
				 ORDER BY i.room_type_id, i.stay_date
				""",
				(rs, i) -> new InventoryMismatch(rs.getLong("room_type_id"), rs.getObject("stay_date", LocalDate.class),
						rs.getInt("total_count"), rs.getInt("booked_count"), rs.getInt("expected")),
				from, to);
	}

	/**
	 * 재고 복구: 한 날짜의 booked_count 를 활성 예약 수로 맞춘다. 운영자가 원인을 확인한 뒤 호출한다 (자동 보정 안 함).
	 *
	 * <p>재고 행을 먼저 잠그고 센다. 예약 생성·만료·취소도 같은 행을 잠그고 바꾸므로,
	 * 진행 중인 트랜잭션은 커밋된 뒤에 세어지거나(잠금 대기) 아직 반영 전이면 이 보정 뒤에 자기 몫을 더한다.
	 */
	@Transactional
	public RecountResult recount(Long roomTypeId, LocalDate stayDate) {
		List<int[]> row = jdbc.query("""
				SELECT total_count, booked_count FROM room_inventory
				 WHERE room_type_id = ? AND stay_date = ? FOR UPDATE
				""", (rs, i) -> new int[] { rs.getInt("total_count"), rs.getInt("booked_count") }, roomTypeId, stayDate);
		if (row.isEmpty()) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "재고가 등록되지 않은 날짜입니다.",
					Map.of("roomTypeId", roomTypeId, "stayDate", stayDate.toString()));
		}
		int total = row.get(0)[0];
		int before = row.get(0)[1];
		int expected = jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE room_type_id = ? "
				+ "AND check_in <= ? AND check_out > ? AND status IN " + ACTIVE_STATUSES,
				Integer.class, roomTypeId, stayDate, stayDate);
		if (expected > total) {
			// 예약 자체가 재고를 넘었다 — 보정으로 해결할 수 없는 상태라 운영자가 재고를 늘리거나 예약을 정리해야 함
			throw new BusinessException(ErrorCode.INVENTORY_BELOW_BOOKED, "활성 예약 수가 재고보다 많아 보정할 수 없습니다.",
					Map.of("totalCount", total, "activeReservations", expected));
		}
		jdbc.update("UPDATE room_inventory SET booked_count = ?, updated_at = now() WHERE room_type_id = ? AND stay_date = ?",
				expected, roomTypeId, stayDate);
		if (before != expected) {
			log.warn("재고 재계산: roomTypeId={}, stayDate={}, booked_count {} → {}", roomTypeId, stayDate, before, expected);
		}
		return new RecountResult(roomTypeId, stayDate, before, expected);
	}

	private void requireRoomType(Long roomTypeId) {
		if (!roomTypeRepository.existsById(roomTypeId)) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "객실 타입을 찾을 수 없습니다.", Map.of("roomTypeId", roomTypeId));
		}
	}
}
