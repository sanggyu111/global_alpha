package com.staypoint.common.error;

import org.springframework.http.HttpStatus;

/**
 * API 에러 코드 목록 (설계 7장). 클라이언트는 message 대신 code 로 분기한다.
 */
public enum ErrorCode {

	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
	MISSING_HEADER(HttpStatus.BAD_REQUEST, "필수 헤더가 없습니다."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."),
	NOT_OWNER(HttpStatus.FORBIDDEN, "본인의 예약만 처리할 수 있습니다."),
	SOLD_OUT(HttpStatus.CONFLICT, "선택한 기간에 남은 객실이 없습니다."),
	RATE_NOT_FOUND(HttpStatus.CONFLICT, "요금이 등록되지 않은 날짜가 포함되어 있습니다."),
	HOLD_EXPIRED(HttpStatus.CONFLICT, "객실 선점 시간이 만료되었습니다."),
	INVALID_STATE(HttpStatus.CONFLICT, "현재 상태에서는 처리할 수 없습니다."),
	PAYMENT_IN_PROGRESS(HttpStatus.CONFLICT, "이미 진행 중인 결제가 있습니다."),
	INVENTORY_BELOW_BOOKED(HttpStatus.CONFLICT, "이미 예약된 수보다 재고를 줄일 수 없습니다."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

	private final HttpStatus status;
	private final String defaultMessage;

	ErrorCode(HttpStatus status, String defaultMessage) {
		this.status = status;
		this.defaultMessage = defaultMessage;
	}

	public HttpStatus status() {
		return status;
	}

	public String defaultMessage() {
		return defaultMessage;
	}
}
