package com.staypoint.reservation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.staypoint.common.StaypointProperties;

/**
 * 주기적으로 만료된 선점을 해제한다. 트랜잭션은 HoldExpiryService 가 건별로 연다
 * (같은 클래스 안에서 호출하면 @Transactional 프록시를 거치지 않으므로 클래스를 나눴다).
 */
@Component
public class HoldExpiryScheduler {

	private static final Logger log = LoggerFactory.getLogger(HoldExpiryScheduler.class);

	private final HoldExpiryService holdExpiryService;
	private final StaypointProperties properties;

	public HoldExpiryScheduler(HoldExpiryService holdExpiryService, StaypointProperties properties) {
		this.holdExpiryService = holdExpiryService;
		this.properties = properties;
	}

	@Scheduled(fixedDelayString = "${staypoint.hold-expiry.interval-ms}")
	public void run() {
		int expired = expireExpiredHolds();
		if (expired > 0) {
			log.info("선점 만료 처리 {}건", expired);
		}
	}

	/**
	 * 한 번 실행에 최대 batch-size 건까지 처리한다. 남은 건은 다음 주기에 처리한다.
	 * 처리 중 예외가 나면 이번 실행을 멈춘다 (같은 예약에서 계속 실패하며 루프를 돌지 않게).
	 */
	public int expireExpiredHolds() {
		int expired = 0;
		try {
			while (expired < properties.holdExpiry().batchSize() && holdExpiryService.expireNext()) {
				expired++;
			}
		} catch (RuntimeException e) {
			log.error("선점 만료 처리 실패 — 다음 주기에 다시 시도", e);
		}
		return expired;
	}
}
