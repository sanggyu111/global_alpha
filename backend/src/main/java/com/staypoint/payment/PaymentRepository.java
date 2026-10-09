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

	/** 응답을 못 받고 READY 로 오래 남은 결제 (상태 확정 스케줄러 대상). */
	@Query("SELECT p.pgOrderId FROM Payment p WHERE p.status = :status AND p.createdAt < :before ORDER BY p.createdAt")
	List<String> findOrderIdsByStatusCreatedBefore(@Param("status") PaymentStatus status,
			@Param("before") Instant before, Limit limit);
}
