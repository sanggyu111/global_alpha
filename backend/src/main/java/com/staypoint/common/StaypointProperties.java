package com.staypoint.common;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml 의 staypoint.* 설정 (설계 9장).
 * 선점 만료 시간, 재시도 횟수처럼 운영 중 바꿀 수 있어야 하는 값은 코드에 박지 않고 여기로 모은다.
 */
@ConfigurationProperties(prefix = "staypoint")
public record StaypointProperties(
		ZoneId zoneId,
		int holdMinutes,
		HoldExpiry holdExpiry,
		Pg pg,
		PaymentReconcile paymentReconcile,
		CancelRetry cancelRetry
) {

	/** 선점 만료 스케줄러: 실행 간격(ms)과 한 번에 처리할 최대 건수. */
	public record HoldExpiry(long intervalMs, int batchSize) {
	}

	/**
	 * PG 호출 설정. baseUrl 이 "/" 로 시작하면 이 앱 자신 기준 (모의 PG).
	 * readTimeoutMs 를 넘기면 응답을 기다리지 않고 PROCESSING 으로 돌려준다.
	 * webhookSecret: PG 통지의 X-Mock-PG-Secret 헤더와 비교할 값.
	 */
	public record Pg(String baseUrl, long connectTimeoutMs, long readTimeoutMs, String webhookSecret) {
	}

	/** 응답을 못 받아 READY 로 남은 결제를 PG 조회로 확정하는 스케줄러. */
	public record PaymentReconcile(long intervalMs, long readyThresholdSeconds, int batchSize) {
	}

	public record CancelRetry(int maxAttempts) {
	}
}
