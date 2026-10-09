package com.staypoint.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @Scheduled 작업(선점 만료, 이후 결제 취소 재시도·결제 상태 확정)을 켠다.
 * 테스트에서는 꺼서 스케줄러가 테스트 데이터를 예고 없이 바꾸지 않게 하고, 테스트가 직접 호출한다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "staypoint.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
