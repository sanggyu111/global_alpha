package com.staypoint.payment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentApproveFailureRepository extends JpaRepository<PaymentApproveFailure, Long> {
}
