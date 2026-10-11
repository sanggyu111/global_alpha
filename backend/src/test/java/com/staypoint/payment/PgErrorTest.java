package com.staypoint.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import com.staypoint.payment.PgClient.PgError;
import com.staypoint.payment.PgClient.PgErrorCode;
import com.staypoint.payment.PgClient.PgRejectedException;
import com.staypoint.payment.PgClient.PgUnavailableException;

/** PG 예외 → 환불 실패 기록의 오류 코드 분류 (설계 8.4 T22). 타임아웃·연결 실패는 통합 테스트로 재현하기 어려워 여기서 확인한다. */
class PgErrorTest {

	@Test
	void HTTP_응답이_있으면_상태_코드로_나누고_코드를_남긴다() {
		assertThat(PgError.of(unavailable(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE))))
				.isEqualTo(new PgError(PgErrorCode.PG_5XX, 503));
		assertThat(PgError.of(new PgRejectedException("x", new HttpClientErrorException(HttpStatus.UNPROCESSABLE_CONTENT))))
				.isEqualTo(new PgError(PgErrorCode.PG_REJECTED, 422));
	}

	@Test
	void 응답이_없으면_타임아웃과_연결_실패를_구분한다() {
		assertThat(PgError.of(unavailable(new ResourceAccessException("x", new SocketTimeoutException("Read timed out")))))
				.isEqualTo(new PgError(PgErrorCode.PG_TIMEOUT, null));
		// 응답 본문을 읽다 타임아웃 — 실제 지연 응답에서 이 모양으로 온다 (L061)
		assertThat(PgError.of(unavailable(new RestClientException("Error while extracting response",
				new SocketTimeoutException("Read timed out")))))
				.isEqualTo(new PgError(PgErrorCode.PG_TIMEOUT, null));
		assertThat(PgError.of(unavailable(new ResourceAccessException("x", new ConnectException("refused")))))
				.isEqualTo(new PgError(PgErrorCode.PG_CONNECTION, null));
		assertThat(PgError.of(unavailable(null))).isEqualTo(new PgError(PgErrorCode.PG_CONNECTION, null)); // PG 주소 미정
		assertThat(PgError.of(unavailable(new RestClientException("x"))))
				.isEqualTo(new PgError(PgErrorCode.PG_UNKNOWN, null));
	}

	private static PgUnavailableException unavailable(Throwable cause) {
		return new PgUnavailableException("x", cause);
	}
}
