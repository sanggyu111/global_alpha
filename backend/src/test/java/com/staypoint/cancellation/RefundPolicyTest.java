package com.staypoint.cancellation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 환불 금액 계산 (기획 5.2, S10). 구간 경계값을 모두 고정한다.
 * 예: 체크인 10/20 → 10/13 까지 100%, 10/14~17 70%, 10/18~19 50%, 10/20 이후 0%.
 */
class RefundPolicyTest {

	static final LocalDate CHECK_IN = LocalDate.of(2026, 10, 20);
	static final BigDecimal PAID = new BigDecimal("200000");

	@ParameterizedTest(name = "취소일 {0} (D={1}) → {2}%, {3}원")
	@CsvSource({
			"2026-10-12,  8, 100, 200000",
			"2026-10-13,  7, 100, 200000", // 경계: 7일 전까지 100%
			"2026-10-14,  6,  70, 140000",
			"2026-10-17,  3,  70, 140000", // 경계: 3일 전 70%
			"2026-10-18,  2,  50, 100000",
			"2026-10-19,  1,  50, 100000", // 경계: 1일 전 50%
			"2026-10-20,  0,   0,      0", // 당일
			"2026-10-21, -1,   0,      0", // 체크인 이후(노쇼)
	})
	void 체크인까지_남은_일수_구간별_환불율과_금액(LocalDate cancelDate, long daysBefore, int percent, String amount) {
		RefundPolicy.Refund refund = RefundPolicy.calculate(PAID, CHECK_IN, cancelDate);

		assertThat(refund.daysBeforeCheckIn()).isEqualTo(daysBefore);
		assertThat(refund.percent()).isEqualTo(percent);
		assertThat(refund.amount()).isEqualByComparingTo(amount);
	}

	@Test
	void 원_단위_미만은_내림한다() {
		// 99,999 × 70% = 69,999.3 → 69,999 / 33,333 × 50% = 16,666.5 → 16,666 (과다 환불 없음)
		assertThat(RefundPolicy.calculate(new BigDecimal("99999"), CHECK_IN, CHECK_IN.minusDays(5)).amount())
				.isEqualByComparingTo("69999");
		assertThat(RefundPolicy.calculate(new BigDecimal("33333"), CHECK_IN, CHECK_IN.minusDays(1)).amount())
				.isEqualByComparingTo("16666");
	}

	@Test
	void 환불액은_소수점_없는_원_단위다() {
		assertThat(RefundPolicy.calculate(new BigDecimal("99999"), CHECK_IN, CHECK_IN.minusDays(5)).amount().scale())
				.isZero();
	}
}
