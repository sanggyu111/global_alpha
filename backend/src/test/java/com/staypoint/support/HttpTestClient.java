package com.staypoint.support;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import com.jayway.jsonpath.JsonPath;

/**
 * 랜덤 포트로 띄운 실제 서버에 HTTP 요청을 보내는 테스트 도우미. 4xx/5xx 도 예외 없이 응답으로 돌려준다.
 * (결제 흐름은 같은 앱 안의 모의 PG 를 HTTP 로 부르므로 MockMvc 가 아니라 실제 서버가 필요하다)
 */
public class HttpTestClient {

	private final RestClient client;

	public HttpTestClient(int port) {
		this.client = RestClient.create("http://localhost:" + port);
	}

	public record Response(int status, String body) {

		public <T> T json(String path) {
			return JsonPath.read(body, path);
		}
	}

	public Response post(String path, Map<String, String> headers, String jsonBody) {
		RestClient.RequestBodySpec spec = client.post().uri(path)
				.headers(h -> headers.forEach(h::add))
				.contentType(MediaType.APPLICATION_JSON);
		if (jsonBody != null) {
			spec.body(jsonBody);
		}
		ResponseEntity<String> response = spec.retrieve()
				.onStatus(status -> true, (request, res) -> {
				})
				.toEntity(String.class);
		return new Response(response.getStatusCode().value(), response.getBody());
	}
}
