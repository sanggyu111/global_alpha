package com.staypoint.cancellation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TimeZone;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.staypoint.support.ApiIntegrationTest;
import com.staypoint.support.HttpTestClient.Response;

/**
 * 시간대 경계 (T25): 환불율의 "오늘" 은 서버 시간대가 아니라 서비스 시간대(Asia/Seoul) 날짜다.
 * 배포 서버(Railway)처럼 JVM 기본 시간대를 UTC 로 두고, 시각을 UTC 15:30 = 서울 다음 날 00:30 으로 고정한다.
 * 체크인이 서울 기준 6일 뒤면 70% 여야 한다 — UTC 날짜로 잘못 세면 7일 전이 되어 100% 가 나온다.
 */
class RefundTimezoneTest extends ApiIntegrationTest {

	/** UTC 2030-01-01 15:30 = 서울 2030-01-02 00:30 */
	static final Instant NOW = Instant.parse("2030-01-01T15:30:00Z");
	static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	static TimeZone originalDefault;

	@TestConfiguration
	static class FixedClockConfig {

		@Bean
		@Primary
		Clock fixedSeoulClock() {
			return Clock.fixed(NOW, SEOUL);
		}
	}

	@Autowired
	@Qualifier("clock")
	Clock applicationClock; // 운영 코드가 만드는 Clock 빈 (ClockConfig)

	@BeforeAll
	static void serverInUtc() {
		originalDefault = TimeZone.getDefault();
		TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC));
	}

	@AfterAll
	static void restoreTimeZone() {
		TimeZone.setDefault(originalDefault);
	}

	@Test
	void 운영_Clock_은_서버_시간대와_무관하게_서울_시간대다() {
		assertThat(applicationClock.getZone()).isEqualTo(SEOUL);
	}

	@Test
	void UTC_서버에서_자정_직후_취소해도_서울_날짜로_환불율을_정한다() {
		LocalDate seoulToday = LocalDate.now(clock);
		assertThat(seoulToday).isEqualTo(LocalDate.of(2030, 1, 2));
		assertThat(LocalDate.ofInstant(NOW, ZoneOffset.UTC)).isEqualTo(LocalDate.of(2030, 1, 1)); // 서버 날짜는 하루 전

		LocalDate checkIn = seoulToday.plusDays(6); // 서울 기준 6일 전 → 70% (UTC 기준이면 7일 전 → 100%)
		long roomType = fixtures.roomType(2, 5, PRICE, checkIn, checkIn.plusDays(1));
		long id = reserve(roomType, checkIn);
		assertThat(pay(id, "pay-tz", "").<String>json("$.result")).isEqualTo("CONFIRMED");

		Response detail = http.get("/api/reservations/" + id, Map.of("X-User-Id", USER));
		assertThat(detail.<Integer>json("$.refundEstimate.daysBeforeCheckIn")).isEqualTo(6);
		assertThat(detail.<Integer>json("$.refundEstimate.percent")).isEqualTo(70);

		Response canceled = http.post("/api/reservations/" + id + "/cancel", Map.of("X-User-Id", USER), null);
		assertThat(canceled.<Integer>json("$.refundAmount")).isEqualTo(PRICE * 70 / 100);
	}
}
