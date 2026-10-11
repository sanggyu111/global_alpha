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

import com.staypoint.admin.AdminDtos.PaymentCancelFailureRecord;
import com.staypoint.admin.AdminDtos.PaymentCancelIssue;
import com.staypoint.common.error.BusinessException;
import com.staypoint.common.error.ErrorCode;
import com.staypoint.payment.PaymentCancel;

/** 운영자 확인 대상 (FR-UI-5): 재시도를 소진했거나 PG 가 거절한 결제 취소 = 고객 돈이 아직 돌아가지 않은 건, 그리고 자동 환불 실패 기록 (T22). */
@Service
@Transactional(readOnly = true)
public class AdminIssueService {

	private final JdbcTemplate jdbc;
	private final Clock clock;

	public AdminIssueService(JdbcTemplate jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	public List<PaymentCancelIssue> paymentCancels(String status) {
		String valid = Arrays.stream(PaymentCancel.Status.values()).map(Enum::name).filter(status::equals).findFirst()
				.orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED, "알 수 없는 상태입니다: " + status,
						Map.of("status", status)));
		return jdbc.query("""
				SELECT c.id, c.reason, c.status, c.cancel_amount, c.attempt_count, c.last_error, c.next_retry_at,
				       c.created_at, c.updated_at, p.id AS payment_id, p.pg_order_id, p.pg_tid,
				       r.id AS reservation_id, r.reservation_no
				  FROM payment_cancel c
				  JOIN payment p     ON p.id = c.payment_id
				  JOIN reservation r ON r.id = p.reservation_id
				 WHERE c.status = ?
				 ORDER BY c.created_at DESC
				 LIMIT 200
				""",
				(rs, i) -> new PaymentCancelIssue(rs.getLong("id"), rs.getString("reason"), rs.getString("status"),
						rs.getBigDecimal("cancel_amount"), rs.getInt("attempt_count"), rs.getString("last_error"),
						rs.getTimestamp("next_retry_at").toInstant(), rs.getTimestamp("created_at").toInstant(),
						rs.getTimestamp("updated_at").toInstant(), rs.getLong("payment_id"), rs.getString("pg_order_id"),
						rs.getString("pg_tid"), rs.getLong("reservation_id"), rs.getString("reservation_no")),
				valid);
	}

	/** 자동 환불 실패 기록 (T22). from·to 는 발생일(서비스 시간대) 기준 양 끝 포함, 최신순. */
	public List<PaymentCancelFailureRecord> paymentCancelFailures(LocalDate from, LocalDate to) {
		AdminRanges.validate(from, to);
		ZoneId zone = clock.getZone();
		return jdbc.query("""
				SELECT f.id, f.payment_cancel_id, c.reason, c.status, f.attempt_no, f.error_code, f.http_status,
				       f.error_message, f.occurred_at, r.id AS reservation_id, r.reservation_no
				  FROM payment_cancel_failure f
				  JOIN payment_cancel c ON c.id = f.payment_cancel_id
				  JOIN payment p        ON p.id = c.payment_id
				  JOIN reservation r    ON r.id = p.reservation_id
				 WHERE f.occurred_at >= ? AND f.occurred_at < ?
				 ORDER BY f.occurred_at DESC, f.id DESC
				 LIMIT 500
				""",
				(rs, i) -> new PaymentCancelFailureRecord(rs.getLong("id"), rs.getLong("payment_cancel_id"),
						rs.getString("reason"), rs.getString("status"), rs.getInt("attempt_no"),
						rs.getString("error_code"), rs.getObject("http_status", Integer.class),
						rs.getString("error_message"), rs.getTimestamp("occurred_at").toInstant(),
						rs.getLong("reservation_id"), rs.getString("reservation_no")),
				Timestamp.from(from.atStartOfDay(zone).toInstant()),
				Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()));
	}

	/**
	 * 실패 기록 삭제 (T22). 해결된(환불 완료) 건의 기록만 지운다 — 미해결 건의 기록은 "고객 돈이 아직 안 돌아갔다" 는 근거라 남긴다.
	 * 확인과 삭제를 한 문장으로 한다. SUCCEEDED 는 끝 상태라 확인 뒤 상태가 바뀌는 경쟁이 없다.
	 * 실패 기록만 지우고 payment_cancel(돈의 흐름) 은 그대로 둔다.
	 */
	@Transactional
	public void deletePaymentCancelFailure(Long id) {
		int deleted = jdbc.update("""
				DELETE FROM payment_cancel_failure f
				 WHERE f.id = ?
				   AND EXISTS (SELECT 1 FROM payment_cancel c WHERE c.id = f.payment_cancel_id AND c.status = 'SUCCEEDED')
				""", id);
		if (deleted == 1) {
			return;
		}
		boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM payment_cancel_failure WHERE id = ?)", Boolean.class, id));
		if (!exists) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "실패 기록을 찾을 수 없습니다.", Map.of("id", id));
		}
		throw new BusinessException(ErrorCode.INVALID_STATE, "환불이 아직 끝나지 않은 건의 기록은 삭제할 수 없습니다.",
				Map.of("id", id));
	}
}
