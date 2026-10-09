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

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT c FROM PaymentCancel c WHERE c.id = :id")
	Optional<PaymentCancel> findByIdForUpdate(@Param("id") Long id);
}
