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
		CancelRetry cancelRetry
) {

	public record CancelRetry(int maxAttempts) {
	}
}
