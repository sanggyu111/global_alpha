package com.staypoint.admin;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.staypoint.admin.AdminDtos.PaymentCancelIssue;
import com.staypoint.common.error.BusinessException;
import com.staypoint.common.error.ErrorCode;
import com.staypoint.payment.PaymentCancel;

/** 운영자 확인 대상 (FR-UI-5): 재시도를 소진했거나 PG 가 거절한 결제 취소 = 고객 돈이 아직 돌아가지 않은 건. */
@Service
@Transactional(readOnly = true)
public class AdminIssueService {

	private final JdbcTemplate jdbc;

	public AdminIssueService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
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
}
