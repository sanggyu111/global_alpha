package com.staypoint.cancellation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 환불 정책 (기획 5.2). 정책 표와 금액 반올림 규칙은 이 클래스 한 곳에만 둔다.
 *
 * <p>D = 체크인 날짜 − 취소 날짜 (날짜 단위, Asia/Seoul). "7일 전까지 100%" 는 D ≥ 7 로 해석한다.
 * <pre>
 *   D ≥ 7      100%
 *   3 ≤ D < 7   70%
 *   1 ≤ D < 3   50%
 *   D ≤ 0        0%  (당일 · 노쇼 · 체크인 이후)
 * </pre>
 * 금액 = 결제 금액 × 환불율, 원 단위 내림 (고객에게 과다 환불하지 않음).
 */
public final class RefundPolicy {

	/** 위에서부터 처음 맞는 구간을 쓴다. */
	private static final List<Tier> TIERS = List.of(
			new Tier(7, 100),
			new Tier(3, 70),
			new Tier(1, 50));

	private record Tier(long minDaysBefore, int percent) {
	}

	public record Refund(long daysBeforeCheckIn, int percent, BigDecimal amount) {
	}

	private RefundPolicy() {
	}

	/** today 는 Asia/Seoul 기준 날짜를 넘겨야 한다 (호출한 쪽이 Clock 으로 구함). */
	public static Refund calculate(BigDecimal paidAmount, LocalDate checkIn, LocalDate today) {
		long daysBefore = ChronoUnit.DAYS.between(today, checkIn);
		int percent = TIERS.stream()
				.filter(tier -> daysBefore >= tier.minDaysBefore())
				.findFirst()
				.map(Tier::percent)
				.orElse(0);
		BigDecimal amount = paidAmount.multiply(BigDecimal.valueOf(percent))
				.divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN);
		return new Refund(daysBefore, percent, amount);
	}
}
