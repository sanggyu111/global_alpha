package com.staypoint.payment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentCancelFailureRepository extends JpaRepository<PaymentCancelFailure, Long> {
}
