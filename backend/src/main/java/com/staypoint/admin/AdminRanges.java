package com.staypoint.admin;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import com.staypoint.common.error.BusinessException;
import com.staypoint.common.error.ErrorCode;

/** 관리자 API 의 날짜 범위 검증. 양 끝 포함, 최대 1년 (한 번에 너무 많은 행을 만들거나 읽지 않게). */
final class AdminRanges {

	static final int MAX_DAYS = 366;

	private AdminRanges() {
	}

	static void validate(LocalDate from, LocalDate to) {
		if (to.isBefore(from)) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED, "to 는 from 이후여야 합니다.",
					Map.of("to", "to 는 from 이후여야 합니다."));
		}
		if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED, "기간은 최대 " + MAX_DAYS + "일입니다.",
					Map.of("to", "기간은 최대 " + MAX_DAYS + "일입니다."));
		}
	}
}
