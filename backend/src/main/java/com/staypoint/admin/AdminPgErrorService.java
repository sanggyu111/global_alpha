package com.staypoint.admin;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.staypoint.admin.AdminDtos.PgErrorRecord;
import com.staypoint.common.error.BusinessException;
import com.staypoint.common.error.ErrorCode;
import com.staypoint.payment.PgClient.PgErrorCode;

/**
 * PG 오류 화면 (설계 8.5 T23): 결제 승인 실패(payment_approve_failure)와 환불 실패(payment_cancel_failure)를 한 목록으로.
 * 해결 여부는 저장하지 않고 원래 건의 현재 상태로 계산한다 — 승인은 payment 가 READY 가 아니면(결과 확정),
 * 환불은 payment_cancel 이 SUCCEEDED 면 해결됨.
 */
@Service
@Transactional(readOnly = true)
public class AdminPgErrorService {

	public enum Kind {
		APPROVE, CANCEL
	}

	private final JdbcTemplate jdbc;
	private final Clock clock;

	public AdminPgErrorService(JdbcTemplate jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** from·to 는 발생일(서비스 시간대) 기준 양 끝 포함. kind · code 가 null 이면 전체. 최신순 최대 500건. */
	public List<PgErrorRecord> search(LocalDate from, LocalDate to, String kind, String code) {
		AdminRanges.validate(from, to);
		String validKind = kind == null ? null : parse(Kind.values(), kind, "kind").name();
		String validCode = code == null ? null : parse(PgErrorCode.values(), code, "code").name();
		ZoneId zone = clock.getZone();
		// 두 기록 테이블을 같은 열 모양으로 맞춰 UNION ALL — 화면은 시간순 한 목록으로 본다
		return jdbc.query("""
				SELECT * FROM (
				    SELECT 'APPROVE' AS kind, f.id, f.occurred_at, r.id AS reservation_id, r.reservation_no,
				           p.id AS payment_id, NULL::int AS attempt_no, NULL::varchar AS cancel_reason,
				           f.error_code, f.http_status, f.error_message,
				           p.status AS current_status, p.status <> 'READY' AS resolved
				      FROM payment_approve_failure f
				      JOIN payment p     ON p.id = f.payment_id
				      JOIN reservation r ON r.id = p.reservation_id
				    UNION ALL
				    SELECT 'CANCEL', f.id, f.occurred_at, r.id, r.reservation_no,
				           p.id, f.attempt_no, c.reason,
				           f.error_code, f.http_status, f.error_message,
				           c.status, c.status = 'SUCCEEDED'
				      FROM payment_cancel_failure f
				      JOIN payment_cancel c ON c.id = f.payment_cancel_id
				      JOIN payment p        ON p.id = c.payment_id
				      JOIN reservation r    ON r.id = p.reservation_id
				) e
				 WHERE e.occurred_at >= ? AND e.occurred_at < ?
				   AND (?::varchar IS NULL OR e.kind = ?)
				   AND (?::varchar IS NULL OR e.error_code = ?)
				 ORDER BY e.occurred_at DESC, e.kind, e.id DESC
				 LIMIT 500
				""",
				(rs, i) -> new PgErrorRecord(rs.getString("kind"), rs.getLong("id"),
						rs.getTimestamp("occurred_at").toInstant(), rs.getLong("reservation_id"),
						rs.getString("reservation_no"), rs.getLong("payment_id"), rs.getObject("attempt_no", Integer.class),
						rs.getString("cancel_reason"), rs.getString("error_code"), rs.getObject("http_status", Integer.class),
						rs.getString("error_message"), rs.getString("current_status"), rs.getBoolean("resolved")),
				Timestamp.from(from.atStartOfDay(zone).toInstant()),
				Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()),
				validKind, validKind, validCode, validCode);
	}

	/**
	 * 기록 삭제. 해결된(결과가 확정된) 건만 — 미해결 건의 기록은 아직 확인할 근거라 남긴다.
	 * 확인과 삭제를 한 문장으로 한다. 원래 건은 미해결 → 해결 방향으로만 바뀌므로 확인 뒤 삭제를 막아야 할 경쟁이 없다.
	 * 기록만 지우고 payment · payment_cancel(돈의 흐름) 은 그대로 둔다.
	 */
	@Transactional
	public void delete(String kind, Long id) {
		Kind k = parse(Kind.values(), kind, "kind");
		String sql = switch (k) {
			case APPROVE -> """
					DELETE FROM payment_approve_failure f
					 WHERE f.id = ?
					   AND EXISTS (SELECT 1 FROM payment p WHERE p.id = f.payment_id AND p.status <> 'READY')
					""";
			case CANCEL -> """
					DELETE FROM payment_cancel_failure f
					 WHERE f.id = ?
					   AND EXISTS (SELECT 1 FROM payment_cancel c WHERE c.id = f.payment_cancel_id AND c.status = 'SUCCEEDED')
					""";
		};
		if (jdbc.update(sql, id) == 1) {
			return;
		}
		String table = k == Kind.APPROVE ? "payment_approve_failure" : "payment_cancel_failure";
		boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM " + table + " WHERE id = ?)", Boolean.class, id));
		if (!exists) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "PG 오류 기록을 찾을 수 없습니다.", Map.of("id", id));
		}
		throw new BusinessException(ErrorCode.INVALID_STATE, "결과가 아직 확정되지 않은 건의 기록은 삭제할 수 없습니다.",
				Map.of("id", id));
	}

	private static <E extends Enum<E>> E parse(E[] values, String value, String field) {
		return Arrays.stream(values).filter(v -> v.name().equals(value)).findFirst()
				.orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED, "알 수 없는 값입니다: " + value,
						Map.of(field, value)));
	}
}
