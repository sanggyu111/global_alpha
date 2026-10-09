package com.staypoint.mockpg;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import jakarta.annotation.PreDestroy;

/**
 * 승인 결과를 우리 서비스에 통지한다 (설계 5장). 실제 PG 처럼 같은 통지를 여러 번, 동시에 보낼 수 있다
 * → 받는 쪽(결제 결과 반영)이 멱등해야 함을 보여준다. 전송 실패는 로그만 남긴다 (모의 PG 는 재전송하지 않음).
 */
@Component
class MockPgWebhookSender {

	static final String SECRET_HEADER = "X-Mock-PG-Secret";
	private static final Logger log = LoggerFactory.getLogger(MockPgWebhookSender.class);

	private final MockPgProperties properties;
	private final Environment environment;
	private final RestClient restClient;
	private final ExecutorService executor = Executors.newFixedThreadPool(4);

	MockPgWebhookSender(MockPgProperties properties, Environment environment) {
		this.properties = properties;
		this.environment = environment;
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(1));
		requestFactory.setReadTimeout(Duration.ofSeconds(5));
		this.restClient = RestClient.builder().requestFactory(requestFactory).build();
	}

	/** 승인 기록이 커밋된 뒤에 호출해야 한다. 응답을 기다리지 않고 바로 돌아온다. */
	void sendAsync(MockPgPayment payment) {
		MockPgProperties.Webhook webhook = properties.webhook();
		if (!webhook.enabled()) {
			return;
		}
		if (webhook.secret() == null || webhook.secret().isBlank()) {
			log.warn("mockpg.webhook.secret(MOCKPG_WEBHOOK_SECRET) 이 비어 있어 웹훅을 보내지 않습니다.");
			return;
		}
		String url = resolveUrl(webhook.url());
		if (url == null) {
			log.warn("웹 서버 포트를 알 수 없어 웹훅을 보내지 않습니다: {}", webhook.url());
			return;
		}
		Map<String, Object> body = Map.of(
				"orderId", payment.orderId(),
				"status", payment.status(),
				"tid", payment.tid() == null ? "" : payment.tid(),
				"amount", payment.amount());
		for (int i = 0; i < webhook.duplicateCount(); i++) {
			executor.submit(() -> post(url, body, webhook.secret()));
		}
	}

	private void post(String url, Map<String, Object> body, String secret) {
		try {
			restClient.post().uri(url)
					.contentType(MediaType.APPLICATION_JSON)
					.header(SECRET_HEADER, secret)
					.body(body)
					.retrieve()
					.toBodilessEntity();
		} catch (RuntimeException e) {
			log.warn("웹훅 전송 실패: orderId={}, url={}, {}", body.get("orderId"), url, e.getMessage());
		}
	}

	/**
	 * 모의 PG 는 같은 앱 안에 있으므로 기본 통지 주소는 "자기 자신" 이다. 실제 포트는 서버가 뜬 뒤에야 정해지므로
	 * (테스트는 랜덤 포트) 시작 시점에 고정하지 않고 보낼 때마다 local.server.port 로 만든다.
	 */
	private String resolveUrl(String url) {
		if (!url.startsWith("/")) {
			return url;
		}
		String port = environment.getProperty("local.server.port");
		return port == null ? null : "http://localhost:" + port + url;
	}

	@PreDestroy
	void shutdown() {
		executor.shutdown();
	}
}
