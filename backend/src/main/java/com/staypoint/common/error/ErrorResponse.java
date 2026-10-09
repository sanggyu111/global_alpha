package com.staypoint.common.error;

import java.util.Map;

/**
 * 모든 API 에러의 공통 응답 형식: { "code": "SOLD_OUT", "message": "...", "details": {...} }
 */
public record ErrorResponse(String code, String message, Map<String, Object> details) {

	public static ErrorResponse of(ErrorCode errorCode, String message, Map<String, Object> details) {
		return new ErrorResponse(errorCode.name(), message, details);
	}
}
