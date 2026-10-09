package com.staypoint.common.error;

import java.util.Map;

/**
 * 비즈니스 규칙 위반을 나타내는 예외. GlobalExceptionHandler 가 ErrorCode 에 맞는 HTTP 응답으로 바꾼다.
 */
public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;
	private final Map<String, Object> details;

	public BusinessException(ErrorCode errorCode) {
		this(errorCode, errorCode.defaultMessage(), Map.of());
	}

	public BusinessException(ErrorCode errorCode, String message) {
		this(errorCode, message, Map.of());
	}

	public BusinessException(ErrorCode errorCode, String message, Map<String, Object> details) {
		super(message);
		this.errorCode = errorCode;
		this.details = details;
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}

	public Map<String, Object> getDetails() {
		return details;
	}
}
