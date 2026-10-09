package com.staypoint.mockpg;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 모의 PG 장애 주입 설정 (설계 5장, FR-PAY-2). 승인·취소 API 는 쿼리 파라미터로 이 기본값을 덮어쓸 수 있다.
 */
@ConfigurationProperties(prefix = "mockpg")
public record MockPgProperties(Approve approve, Cancel cancel, Webhook webhook) {

	/** failRate: 승인 실패(거절) 확률 0~1, delayMs: 승인을 기록한 뒤 응답하기까지의 지연. */
	public record Approve(double failRate, long delayMs) {
	}

	/** failRate: 취소 요청에 5xx(503)로 응답할 확률 0~1. 이때 취소는 기록되지 않는다. */
	public record Cancel(double failRate) {
	}

	/**
	 * 승인 결과 통지. url 이 "/" 로 시작하면 이 앱 자신의 주소 기준 경로로 본다.
	 * duplicateCount: 같은 통지를 몇 번 보낼지 (중복 통지 방어를 보여주기 위함).
	 * secret: X-Mock-PG-Secret 헤더 값. 비어 있으면 통지를 보내지 않는다.
	 */
	public record Webhook(boolean enabled, int duplicateCount, String url, String secret) {
	}
}
