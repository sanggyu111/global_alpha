package com.staypoint.admin;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.staypoint.admin.AdminDtos.AdminReservation;
import com.staypoint.admin.AdminDtos.Page;
import com.staypoint.common.error.BusinessException;
import com.staypoint.common.error.ErrorCode;
import com.staypoint.reservation.ReservationStatus;

/**
 * 관리자 예약 목록 (FR-UI-3): 상태 · 숙소 · 체크인 날짜 필터 + 페이지네이션.
 * 필터가 있을 때만 WHERE 조건을 붙이는 동적 조회라 SQL 을 직접 조립한다 (값은 모두 ? 바인딩).
 */
@Service
@Transactional(readOnly = true)
public class AdminReservationService {

	static final int MAX_PAGE_SIZE = 100;

	private final JdbcTemplate jdbc;

	public AdminReservationService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** from · to 는 체크인 날짜 기준 (양 끝 포함). page 는 0 부터. */
	public Page<AdminReservation> search(String status, Long propertyId, LocalDate from, LocalDate to, int page,
			int size) {
		if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED,
					"page 는 0 이상, size 는 1~" + MAX_PAGE_SIZE + " 이어야 합니다.", Map.of("size", size));
		}
		StringBuilder where = new StringBuilder(" WHERE 1 = 1");
		List<Object> args = new ArrayList<>();
		if (status != null && !status.isBlank()) {
			where.append(" AND r.status = ?");
			args.add(validStatus(status));
		}
		if (propertyId != null) {
			where.append(" AND rt.property_id = ?");
			args.add(propertyId);
		}
		if (from != null) {
			where.append(" AND r.check_in >= ?");
			args.add(from);
		}
		if (to != null) {
			where.append(" AND r.check_in <= ?");
			args.add(to);
		}
		String fromClause = """
				  FROM reservation r
				  JOIN room_type rt ON rt.id = r.room_type_id
				  JOIN property p   ON p.id = rt.property_id
				""";

		long total = jdbc.queryForObject("SELECT COUNT(*)" + fromClause + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(size);
		pageArgs.add((long) page * size);
		List<AdminReservation> content = jdbc.query("""
				SELECT r.id, r.reservation_no, r.user_id, r.status, p.id AS property_id, p.name AS property_name,
				       rt.id AS room_type_id, rt.name AS room_type_name, r.check_in, r.check_out, r.guest_count,
				       r.guest_name, r.total_amount, r.cancel_reason, r.created_at
				""" + fromClause + where + " ORDER BY r.created_at DESC, r.id DESC LIMIT ? OFFSET ?",
				(rs, i) -> new AdminReservation(rs.getLong("id"), rs.getString("reservation_no"),
						rs.getString("user_id"), rs.getString("status"), rs.getLong("property_id"),
						rs.getString("property_name"), rs.getLong("room_type_id"), rs.getString("room_type_name"),
						rs.getObject("check_in", LocalDate.class), rs.getObject("check_out", LocalDate.class),
						rs.getInt("guest_count"), rs.getString("guest_name"), rs.getBigDecimal("total_amount"),
						rs.getString("cancel_reason"), rs.getTimestamp("created_at").toInstant()),
				pageArgs.toArray());
		return Page.of(content, page, size, total);
	}

	private static String validStatus(String status) {
		return Arrays.stream(ReservationStatus.values())
				.map(Enum::name)
				.filter(status::equals)
				.findFirst()
				.orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED, "알 수 없는 예약 상태입니다: " + status,
						Map.of("status", status)));
	}
}
