package com.staypoint.payment;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

	Optional<Payment> findByIdempotencyKey(String idempotencyKey);

	boolean existsByReservationIdAndStatusIn(Long reservationId, Collection<PaymentStatus> statuses);

	/** 예약의 가장 최근 결제 시도 (화면 표시용). */
	Optional<Payment> findFirstByReservationIdOrderByIdDesc(Long reservationId);

	/**
	 * 잠그기 전에 예약 id 만 알아낸다. 엔티티를 먼저 읽어 두면 뒤의 잠금 조회가 영속성 컨텍스트에 있던
	 * (잠그기 전의 낡은) 상태를 그대로 돌려주므로, 잠금 조회가 이 결제의 첫 조회가 되게 한다.
	 */
	@Query("SELECT p.reservationId FROM Payment p WHERE p.pgOrderId = :orderId")
	Optional<Long> findReservationIdByPgOrderId(@Param("orderId") String orderId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM Payment p WHERE p.pgOrderId = :orderId")
	Optional<Payment> findByPgOrderIdForUpdate(@Param("orderId") String orderId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM Payment p WHERE p.id = :id")
	Optional<Payment> findByIdForUpdate(@Param("id") Long id);

	/** 예약의 승인된 결제 (최대 1건 — 부분 UNIQUE 인덱스). 취소 시 예약 다음 순서로 잠근다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM Payment p WHERE p.reservationId = :reservationId AND p.status = 'APPROVED'")
	Optional<Payment> findApprovedByReservationIdForUpdate(@Param("reservationId") Long reservationId);

	/** 응답을 못 받고 READY 로 오래 남은 결제 (상태 확정 스케줄러 대상). */
	@Query("SELECT p.pgOrderId FROM Payment p WHERE p.status = :status AND p.createdAt < :before ORDER BY p.createdAt")
	List<String> findOrderIdsByStatusCreatedBefore(@Param("status") PaymentStatus status,
			@Param("before") Instant before, Limit limit);
}
