package com.staypoint.common.error;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 예외를 공통 에러 형식(ErrorResponse)으로 변환한다. 컨트롤러는 예외를 던지기만 하면 된다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
		return respond(e.getErrorCode(), e.getMessage(), e.getDetails());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
		Map<String, Object> fieldErrors = new LinkedHashMap<>();
		e.getBindingResult().getFieldErrors()
				.forEach(error -> fieldErrors.put(error.getField(), error.getDefaultMessage()));
		return respond(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), fieldErrors);
	}

	// JSON 형식 오류, 날짜 형식(yyyy-MM-dd) 오류 등 본문을 읽을 수 없는 요청
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
		return respond(ErrorCode.VALIDATION_FAILED, "요청 본문을 읽을 수 없습니다.", Map.of());
	}

	@ExceptionHandler(MissingRequestHeaderException.class)
	public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
		return respond(ErrorCode.MISSING_HEADER, e.getHeaderName() + " 헤더가 필요합니다.",
				Map.of("header", e.getHeaderName()));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
		// 예상하지 못한 예외는 내부 정보를 응답에 노출하지 않고 로그로만 남긴다
		log.error("Unhandled exception", e);
		return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), Map.of());
	}

	private ResponseEntity<ErrorResponse> respond(ErrorCode code, String message, Map<String, Object> details) {
		return ResponseEntity.status(code.status()).body(ErrorResponse.of(code, message, details));
	}
}
