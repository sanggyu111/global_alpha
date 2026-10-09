package com.staypoint.payment;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import com.staypoint.common.SelfUrl;
import com.staypoint.common.StaypointProperties;

/**
 * PG HTTP 클라이언트. 모의 PG 가 같은 앱에 있어도 반드시 HTTP 로 부른다 → 타임아웃·5xx 가 실제처럼 일어난다.
 * 호출하는 쪽은 DB 트랜잭션 밖에서 이 클래스를 써야 한다 (네트워크를 기다리며 잠금·커넥션을 쥐지 않기 위해).
 */
@Component
public class PgClient {

	private final StaypointProperties.Pg properties;
	private final SelfUrl selfUrl;
	private final RestClient restClient;

	public PgClient(StaypointProperties properties, SelfUrl selfUrl) {
		this.properties = properties.pg();
		this.selfUrl = selfUrl;
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofMillis(this.properties.connectTimeoutMs()));
		requestFactory.setReadTimeout(Duration.ofMillis(this.properties.readTimeoutMs()));
		this.restClient = RestClient.builder().requestFactory(requestFactory).build();
	}

	/** PG 의 승인 결과. approved=false 면 거절 (재결제 가능). */
	public record PgApproval(boolean approved, String tid, BigDecimal amount, String failReason) {

		public static PgApproval declined(String reason) {
			return new PgApproval(false, null, null, reason);
		}
	}

	/** 결과를 모름 — 타임아웃·연결 실패·5xx. PG 에서는 처리됐을 수도 있으므로 "실패" 로 단정하면 안 된다. */
	public static class PgUnavailableException extends RuntimeException {
		public PgUnavailableException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/** PG 가 4xx 로 거절 — 같은 요청을 다시 보내도 결과가 같다 (재시도 무의미). */
	public static class PgRejectedException extends RuntimeException {
		public PgRejectedException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	record ApproveResponse(String orderId, String status, String tid, BigDecimal amount) {

		PgApproval toApproval() {
			// CANCELED 도 "승인된 적 있는" 결제다
			return "FAILED".equals(status) ? PgApproval.declined("PG 승인 거절") : new PgApproval(true, tid, amount, null);
		}
	}

	/**
	 * 승인 요청. failRate·delayMs 는 데모용 장애 주입 값으로 모의 PG 에 그대로 전달한다 (null 이면 PG 기본값).
	 * 같은 orderId 로 다시 부르면 PG 가 처음 결과를 돌려주므로 타임아웃 후 재시도해도 이중 승인이 없다.
	 */
	public PgApproval approve(String orderId, BigDecimal amount, Double failRate, Long delayMs) {
		UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(baseUrl() + "/payments/approve");
		if (failRate != null) {
			uri.queryParam("failRate", failRate);
		}
		if (delayMs != null) {
			uri.queryParam("delayMs", delayMs);
		}
		return call(() -> restClient.post().uri(uri.build().toUri())
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("orderId", orderId, "amount", amount))
				.retrieve()
				.body(ApproveResponse.class)).toApproval();
	}

	/** 승인 기록 조회. PG 에 기록이 없으면(404) 비어 있다 = 승인 요청이 PG 에 닿지 않았다. */
	public Optional<PgApproval> find(String orderId) {
		try {
			return Optional.of(call(() -> restClient.get().uri(baseUrl() + "/payments/{orderId}", orderId)
					.retrieve()
					.body(ApproveResponse.class)).toApproval());
		} catch (PgRejectedException e) {
			if (e.getCause() instanceof HttpClientErrorException http && http.getStatusCode() == HttpStatus.NOT_FOUND) {
				return Optional.empty();
			}
			throw e;
		}
	}

	/** 취소 요청. 같은 cancelKey 로 다시 부르면 PG 가 이전 결과를 돌려준다 (이중 환불 없음). */
	public void cancel(String tid, String cancelKey, BigDecimal amount) {
		call(() -> restClient.post().uri(baseUrl() + "/payments/{tid}/cancel", tid)
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("cancelKey", cancelKey, "amount", amount))
				.retrieve()
				.toBodilessEntity());
	}

	private interface Call<T> {
		T run();
	}

	private static <T> T call(Call<T> call) {
		try {
			return call.run();
		} catch (HttpClientErrorException e) {
			throw new PgRejectedException("PG 거절 " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(), e);
		} catch (RestClientException e) {
			// 5xx(HttpServerErrorException), 타임아웃·연결 실패(ResourceAccessException) 등
			throw new PgUnavailableException("PG 응답 없음: " + e.getMessage(), e);
		}
	}

	private String baseUrl() {
		return selfUrl.resolve(properties.baseUrl())
				.orElseThrow(() -> new PgUnavailableException("PG 주소를 알 수 없습니다 (웹 서버 포트 미정): "
						+ properties.baseUrl(), null));
	}
}
