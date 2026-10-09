package com.staypoint.payment;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
}
