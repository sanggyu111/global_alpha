package com.staypoint.mockpg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import com.staypoint.TestcontainersConfiguration;
import com.staypoint.support.TestFixtures;

/**
 * 모의 PG 승인 통지(웹훅) — 실제 HTTP 로 보내므로 랜덤 포트 서버를 띄운다.
 * 받는 쪽(결제 결과 반영, T07)이 아직 없으므로 테스트 전용 수신 컨트롤러로 받은 요청을 기록한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"mockpg.webhook.enabled=true",
		"mockpg.webhook.duplicate-count=3",
		"mockpg.webhook.url=/test/webhook-capture",
})
@Import(TestcontainersConfiguration.class)
class MockPgWebhookTest {

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	WebhookCapture capture;

	RestClient client;

	@BeforeEach
	void setUp() {
		new TestFixtures(jdbc).truncateAll();
		capture.received.clear();
		client = RestClient.create("http://localhost:" + port);
	}

	@Test
	void 승인되면_같은_통지를_설정한_횟수만큼_비밀값_헤더와_함께_보낸다() {
		approve("O-1");

		await().atMost(Duration.ofSeconds(5)).until(() -> capture.received.size() == 3);
		assertThat(capture.received).allSatisfy(r -> {
			assertThat(r.secret()).isEqualTo("test-secret");
			assertThat(r.body()).containsEntry("orderId", "O-1").containsEntry("status", "APPROVED");
			assertThat((String) r.body().get("tid")).isNotBlank();
		});
		assertThat(capture.received).extracting(Received::body).containsOnly(capture.received.get(0).body());
	}

	@Test
	void 같은_orderId_재요청에는_통지를_다시_보내지_않는다() {
		approve("O-1");
		await().atMost(Duration.ofSeconds(5)).until(() -> capture.received.size() == 3);

		approve("O-1");

		// 재요청분 통지가 있다면 도착했을 시간만큼 기다린 뒤에도 3건 그대로여야 한다
		await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
				.until(() -> capture.received.size() == 3);
	}

	private void approve(String orderId) {
		client.post().uri("/mock-pg/payments/approve?failRate=0")
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("orderId", orderId, "amount", 100_000))
				.retrieve()
				.toBodilessEntity();
	}

	record Received(String secret, Map<String, Object> body) {
	}

	/** 테스트 클래스 안의 중첩 클래스라 다른 테스트의 컴포넌트 스캔에는 잡히지 않고, 이 테스트에서만 등록된다. */
	@RestController
	static class WebhookCapture {

		final List<Received> received = new CopyOnWriteArrayList<>();

		@PostMapping("/test/webhook-capture")
		void receive(@RequestHeader(MockPgWebhookSender.SECRET_HEADER) String secret,
				@RequestBody Map<String, Object> body) {
			received.add(new Received(secret, body));
		}
	}

	@TestConfiguration
	static class CaptureConfig {

		@Bean
		WebhookCapture webhookCapture() {
			return new WebhookCapture();
		}
	}
}
