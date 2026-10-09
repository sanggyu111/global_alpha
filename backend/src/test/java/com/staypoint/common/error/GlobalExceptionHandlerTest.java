package com.staypoint.common.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예외가 공통 에러 형식 { code, message, details } 와 올바른 HTTP 상태로 바뀌는지 검증한다.
 * DB 가 필요 없는 단위 테스트 (standalone MockMvc).
 */
class GlobalExceptionHandlerTest {

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new FakeController())
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	void 비즈니스_예외는_에러코드의_HTTP_상태와_코드로_응답한다() throws Exception {
		mockMvc.perform(get("/sold-out"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SOLD_OUT"))
				.andExpect(jsonPath("$.message").value(ErrorCode.SOLD_OUT.defaultMessage()))
				.andExpect(jsonPath("$.details.roomTypeId").value(7));
	}

	@Test
	void 검증_실패는_필드별_메시지를_details_에_담는다() throws Exception {
		mockMvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.details.name").exists());
	}

	@Test
	void 필수_헤더가_없으면_MISSING_HEADER() throws Exception {
		mockMvc.perform(get("/needs-header"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MISSING_HEADER"))
				.andExpect(jsonPath("$.details.header").value("X-User-Id"));
	}

	@Test
	void 예상하지_못한_예외는_내부_메시지를_노출하지_않는다() throws Exception {
		mockMvc.perform(get("/boom"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.message").value(ErrorCode.INTERNAL_ERROR.defaultMessage()));
	}

	@Test
	void 없는_경로는_500이_아니라_404_NOT_FOUND() throws Exception {
		mockMvc.perform(get("/no-such-path"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void 지원하지_않는_메서드는_405() throws Exception {
		mockMvc.perform(post("/sold-out"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
	}

	@Test
	void 쿼리_파라미터_타입이_틀리면_400() throws Exception {
		mockMvc.perform(get("/typed").param("rate", "abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	record NameRequest(@NotBlank String name) {
	}

	@RestController
	static class FakeController {

		@GetMapping("/sold-out")
		void soldOut() {
			throw new BusinessException(ErrorCode.SOLD_OUT, ErrorCode.SOLD_OUT.defaultMessage(),
					Map.of("roomTypeId", 7));
		}

		@PostMapping("/validate")
		void validate(@Valid @RequestBody NameRequest request) {
		}

		@GetMapping("/needs-header")
		void needsHeader(@RequestHeader("X-User-Id") String userId) {
		}

		@GetMapping("/typed")
		void typed(@RequestParam double rate) {
		}

		@GetMapping("/boom")
		void boom() {
			throw new IllegalStateException("secret internal detail");
		}
	}
}
