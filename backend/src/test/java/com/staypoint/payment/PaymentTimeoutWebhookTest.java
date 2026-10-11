package com.staypoint.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.staypoint.support.ApiIntegrationTest;
import com.staypoint.support.HttpTestClient.Response;

/**
 * 결제 데모 "응답 지연 5초" 의 흐름 (T27): PG 응답 타임아웃 → PROCESSING → PG 웹훅으로 확정.
 * 모의 PG 는 지연이 끝난 뒤 웹훅을 보내므로, 타임아웃 시점에는 아직 확정되지 않은 상태를 볼 수 있어야 한다.
 */
@TestPropertySource(properties = "mockpg.webhook.enabled=true") // 기본 통지 주소 = 이 앱의 /api/payments/webhook
class PaymentTimeoutWebhookTest extends ApiIntegrationTest {

	@Test
	void PG_응답이_타임아웃되면_PROCESSING_이고_지연이_끝난_뒤_웹훅으로_확정된다() {
		long id = reserve();

		// PG 는 승인을 기록한 뒤 2.5초 후 응답 · 웹훅 → 우리 읽기 타임아웃(1.5초)이 먼저 끝난다
		Response r = pay(id, "pay-1", "?delayMs=2500");

		assertThat(r.<String>json("$.result")).isEqualTo("PROCESSING");
		assertThat(r.<String>json("$.paymentStatus")).isEqualTo("READY");
		assertThat(reservationStatus(id)).isEqualTo("PENDING");
		// PG 쪽에는 승인이 이미 남아 있다 — 응답을 못 받았을 뿐
		assertThat(jdbc.queryForObject("SELECT status FROM mockpg_payment", String.class)).isEqualTo("APPROVED");

		await().atMost(Duration.ofSeconds(5)).until(() -> reservationStatus(id).equals("CONFIRMED"));
		// 웹훅은 2회 중복 전송되지만 확정은 한 번
		await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).until(() -> count(
				"SELECT COUNT(*) FROM reservation_history WHERE reservation_id = ? AND to_status = 'CONFIRMED'", id) == 1);
		assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE reservation_id = ? AND to_status = 'CONFIRMED' "
				+ "AND actor = 'system:pg-webhook'", id)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT status FROM payment", String.class)).isEqualTo("APPROVED");
	}
}
