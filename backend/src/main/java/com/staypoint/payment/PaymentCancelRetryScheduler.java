package com.staypoint.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 결제 취소 재시도 스케줄러. 트랜잭션·시도는 PaymentCancelRetryService 가 한다. */
@Component
public class PaymentCancelRetryScheduler {

	private static final Logger log = LoggerFactory.getLogger(PaymentCancelRetryScheduler.class);

	private final PaymentCancelRetryService retryService;

	public PaymentCancelRetryScheduler(PaymentCancelRetryService retryService) {
		this.retryService = retryService;
	}

	@Scheduled(fixedDelayString = "${staypoint.cancel-retry.interval-ms}")
	public void run() {
		int attempted = retryService.retryDue();
		if (attempted > 0) {
			log.info("결제 취소 재시도 {}건", attempted);
		}
	}
}
