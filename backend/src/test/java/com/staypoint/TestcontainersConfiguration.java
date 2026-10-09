package com.staypoint;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트용 PostgreSQL 컨테이너. 운영과 같은 15 버전을 쓴다 (H2 금지 — 락·제약 동작이 다름, AGENT.md 7장).
 * @ServiceConnection 이 컨테이너 접속 정보를 datasource 설정에 자동으로 연결한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:15-alpine"));
	}

}
