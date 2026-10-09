package com.staypoint.payment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface PaymentCancelRepository extends JpaRepository<PaymentCancel, Long> {

	boolean existsByPaymentIdAndReason(Long paymentId, PaymentCancel.Reason reason);

	Optional<PaymentCancel> findByPaymentIdAndReason(Long paymentId, PaymentCancel.Reason reason);

	/** 예약의 결제 취소 요청 (예: 사용자 취소 환불). */
	@Query("""
			SELECT c FROM PaymentCancel c, Payment p
			 WHERE c.paymentId = p.id AND p.reservationId = :reservationId AND c.reason = :reason
			""")
	Optional<PaymentCancel> findByReservationIdAndReason(@Param("reservationId") Long reservationId,
			@Param("reason") PaymentCancel.Reason reason);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT c FROM PaymentCancel c WHERE c.id = :id")
	Optional<PaymentCancel> findByIdForUpdate(@Param("id") Long id);

	/**
	 * 재시도할 때가 된 취소 요청을 잠가서 가져온다 (설계 4.6).
	 * SKIP LOCKED: 다른 스케줄러가 잡고 있는 행은 기다리지 않고 건너뛴다 → 같은 건을 두 곳에서 동시에 시도하지 않음.
	 */
	@Query(value = """
			SELECT id FROM payment_cancel
			 WHERE status = 'PENDING' AND next_retry_at <= :now
			 ORDER BY next_retry_at
			 LIMIT :limit
			 FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<Long> lockDueIds(@Param("now") Instant now, @Param("limit") int limit);

	/** 집어 간 건을 "처리 중" 으로 표시: next_retry_at 을 미뤄 다른 스케줄러가 다시 집지 않게 한다. */
	@Modifying
	@Query("UPDATE PaymentCancel c SET c.nextRetryAt = :until WHERE c.id IN :ids")
	int postpone(@Param("ids") List<Long> ids, @Param("until") Instant until);
}
