package com.staypoint.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * "지금 몇 시인가" 를 Clock 빈 하나로 통일한다.
 * 환불율(체크인까지 남은 일수)과 선점 만료는 시간에 따라 결과가 달라지므로,
 * 코드에서 LocalDate.now() 를 직접 부르지 않고 Clock 을 주입받아야 테스트에서 시간을 고정할 수 있다.
 * 기준 시간대는 Asia/Seoul (기획 5.2).
 */
@Configuration
public class ClockConfig {

	@Bean
	public Clock clock(StaypointProperties properties) {
		return Clock.system(properties.zoneId());
	}
}
