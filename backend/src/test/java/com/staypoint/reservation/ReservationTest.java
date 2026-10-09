package com.staypoint.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.staypoint.common.error.BusinessException;
import com.staypoint.common.error.ErrorCode;

/** 예약 상태 전이 규칙과 선점 만료 · 결제 가능 검사 (DB 없이 도메인만). */
class ReservationTest {

	static final Instant CREATED = Instant.parse("2026-10-09T00:00:00Z");
	static final Instant EXPIRES = CREATED.plus(Duration.ofMinutes(10));

	@ParameterizedTest(name = "{0} → {1} : {2}")
	@CsvSource({
			"PENDING,   CONFIRMED, true",
			"PENDING,   CANCELED,  true",
			"PENDING,   COMPLETED, false",
			"CONFIRMED, CANCELED,  true",
			"CONFIRMED, COMPLETED, true",
			"CONFIRMED, PENDING,   false",
			"CANCELED,  PENDING,   false",
			"CANCELED,  CONFIRMED, false",
			"CANCELED,  CANCELED,  false",
			"COMPLETED, CANCELED,  false",
	})
	void 허용된_상태_전이만_가능하다(ReservationStatus from, ReservationStatus to, boolean allowed) {
		assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
	}

	@Test
	void 만료_시각이_지나면_CANCELED_와_HOLD_EXPIRED_사유로_바뀐다() {
		Reservation r = pending();

		ReservationStatus from = r.expire(EXPIRES);

		assertThat(from).isEqualTo(ReservationStatus.PENDING);
		assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELED);
		assertThat(r.getCancelReason()).isEqualTo(Reservation.CANCEL_REASON_HOLD_EXPIRED);
		assertThat(r.getCanceledAt()).isEqualTo(EXPIRES);
	}

	@Test
	void 만료_시각_전에는_만료시킬_수_없다() {
		Reservation r = pending();

		assertThatThrownBy(() -> r.expire(EXPIRES.minusMillis(1)))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.INVALID_STATE);
		assertThat(r.getStatus()).isEqualTo(ReservationStatus.PENDING);
	}

	@Test
	void 이미_만료된_예약을_다시_만료시키면_허용되지_않은_전이다() {
		Reservation r = pending();
		r.expire(EXPIRES);

		assertThatThrownBy(() -> r.expire(EXPIRES.plusSeconds(60)))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.INVALID_STATE);
	}

	@Test
	void 선점이_30초_넘게_남아_있으면_결제할_수_있다() {
		assertThatCode(() -> pending().assertPayable(CREATED)).doesNotThrowAnyException();
		assertThatCode(() -> pending().assertPayable(EXPIRES.minusSeconds(31))).doesNotThrowAnyException();
	}

	@Test
	void 선점이_30초_이하로_남았거나_지났으면_HOLD_EXPIRED_로_거부한다() {
		// 스케줄러가 아직 만료 처리를 안 해서 PENDING 이어도 시각 기준으로 거부해야 한다 (S4)
		assertPayableFails(pending(), EXPIRES.minusSeconds(30), ErrorCode.HOLD_EXPIRED);
		assertPayableFails(pending(), EXPIRES, ErrorCode.HOLD_EXPIRED);
		assertPayableFails(pending(), EXPIRES.plusSeconds(60), ErrorCode.HOLD_EXPIRED);
	}

	@Test
	void 만료_처리된_예약은_INVALID_STATE_로_결제를_거부한다() {
		Reservation r = pending();
		r.expire(EXPIRES);

		assertPayableFails(r, EXPIRES, ErrorCode.INVALID_STATE);
	}

	private static void assertPayableFails(Reservation r, Instant now, ErrorCode expected) {
		assertThatThrownBy(() -> r.assertPayable(now))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(expected);
	}

	private static Reservation pending() {
		LocalDate checkIn = LocalDate.of(2026, 10, 20);
		return Reservation.hold("R261009-TEST0001", "user-1", "key-1", 1L, checkIn, checkIn.plusDays(1), 2,
				"홍길동", "010-0000-0000", new BigDecimal("100000"), CREATED, EXPIRES);
	}
}
