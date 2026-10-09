package com.staypoint.common;

import java.util.Optional;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 같은 앱 안의 모의 PG ↔ 결제 API 가 서로를 HTTP 로 부를 때 쓰는 주소.
 * 실제 포트는 서버가 뜬 뒤에야 정해지므로(테스트는 랜덤 포트) 시작 시점에 고정하지 않고
 * 호출할 때마다 local.server.port 로 만든다. "/" 로 시작하지 않는 값은 절대 주소로 그대로 쓴다.
 */
@Component
public class SelfUrl {

	private final Environment environment;

	public SelfUrl(Environment environment) {
		this.environment = environment;
	}

	/** 서버가 아직 안 떴으면(예: MockMvc 테스트) 비어 있다. */
	public Optional<String> resolve(String urlOrPath) {
		if (!urlOrPath.startsWith("/")) {
			return Optional.of(urlOrPath);
		}
		String port = environment.getProperty("local.server.port");
		return Optional.ofNullable(port).map(p -> "http://localhost:" + p + urlOrPath);
	}
}
