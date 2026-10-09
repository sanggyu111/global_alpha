package com.staypoint.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 결제 상태 확정 스케줄러: PG 응답을 못 받아 READY 로 남은 결제를 주기적으로 PG 에 조회해 확정한다.
 * 웹훅이 먼저 와서 반영됐다면 applyResult 가 아무것도 하지 않으므로 둘이 겹쳐도 안전하다.
 */
@Component
public class PaymentReconcileScheduler {

	private static final Logger log = LoggerFactory.getLogger(PaymentReconcileScheduler.class);

	private final PaymentService paymentService;

	public PaymentReconcileScheduler(PaymentService paymentService) {
		this.paymentService = paymentService;
	}

	@Scheduled(fixedDelayString = "${staypoint.payment-reconcile.interval-ms}")
	public void run() {
		int checked = paymentService.reconcileStaleReady();
		if (checked > 0) {
			log.info("결제 상태 확정 {}건", checked);
		}
	}
}
