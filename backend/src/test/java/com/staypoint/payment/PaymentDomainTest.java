package com.staypoint.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.staypoint.common.error.BusinessException;

/** 결제 · 결제 취소 요청의 상태 규칙 (DB 없이). */
class PaymentDomainTest {

	static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");

	@Test
	void 결제_상태는_READY_에서_APPROVED_또는_FAILED_로만_간다() {
		Payment approved = ready();
		approved.approve("T-1", NOW);
		assertThat(approved.getStatus()).isEqualTo(PaymentStatus.APPROVED);
		assertThatThrownBy(() -> approved.fail("late", NOW)).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> approved.approve("T-2", NOW)).isInstanceOf(BusinessException.class);

		Payment failed = ready();
		failed.fail("PG 승인 거절", NOW);
		assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThatThrownBy(() -> failed.approve("T-1", NOW)).isInstanceOf(BusinessException.class);
	}

	@Test
	void 부분_취소는_APPROVED_를_유지하고_전액이_취소되면_CANCELED() {
		Payment p = ready();
		p.approve("T-1", NOW);

		p.applyCancel(new BigDecimal("30000"), NOW);
		assertThat(p.getStatus()).isEqualTo(PaymentStatus.APPROVED);
		assertThat(p.getCanceledAmount()).isEqualByComparingTo("30000");

		p.applyCancel(new BigDecimal("70000"), NOW);
		assertThat(p.getStatus()).isEqualTo(PaymentStatus.CANCELED);
		assertThatThrownBy(() -> p.applyCancel(BigDecimal.ONE, NOW)).isInstanceOf(BusinessException.class);
	}

	@Test
	void 승인되지_않은_결제는_취소를_반영할_수_없다() {
		assertThatThrownBy(() -> ready().applyCancel(BigDecimal.ONE, NOW)).isInstanceOf(BusinessException.class);
	}

	@Test
	void 취소_재시도는_1_2_4_8분_간격이고_5번째_실패에서_운영자_확인_대상이_된다() {
		PaymentCancel c = compensation();

		for (int attempt = 1; attempt <= 4; attempt++) {
			c.recordFailure("PG 응답 없음", true, 5, NOW);
			assertThat(c.getStatus()).isEqualTo(PaymentCancel.Status.PENDING);
			assertThat(c.getNextRetryAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1L << (attempt - 1))));
		}
		c.recordFailure("PG 응답 없음", true, 5, NOW);

		assertThat(c.getAttemptCount()).isEqualTo(5);
		assertThat(c.getStatus()).isEqualTo(PaymentCancel.Status.MANUAL_REVIEW);
	}

	@Test
	void PG_가_거절한_취소는_재시도하지_않고_바로_운영자_확인_대상이_된다() {
		PaymentCancel c = compensation();

		c.recordFailure("PG 거절 404", false, 5, NOW);

		assertThat(c.getAttemptCount()).isEqualTo(1);
		assertThat(c.getStatus()).isEqualTo(PaymentCancel.Status.MANUAL_REVIEW);
	}

	@Test
	void 너무_긴_오류_메시지는_컬럼_길이에_맞게_자른다() {
		PaymentCancel c = compensation();
		c.recordFailure("x".repeat(1000), true, 5, NOW);
		assertThat(c.getLastError()).hasSize(500);
	}

	private static Payment ready() {
		return Payment.ready(1L, "P-1", new BigDecimal("100000"), "pay-key", NOW);
	}

	private static PaymentCancel compensation() {
		Payment p = ready();
		p.approve("T-1", NOW);
		return PaymentCancel.compensation(p, NOW);
	}
}
