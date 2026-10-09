package com.staypoint.reservation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.staypoint.reservation.dto.CreateReservationRequest;
import com.staypoint.reservation.dto.ReservationResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

	private final ReservationService reservationService;

	public ReservationController(ReservationService reservationService) {
		this.reservationService = reservationService;
	}

	/**
	 * 예약 생성 (재고 선점). 새로 만들면 201, 같은 Idempotency-Key 재요청이면 기존 예약을 200 으로 돌려준다.
	 * Idempotency-Key 는 클라이언트가 예약 폼마다 한 번 만들어 재시도에도 같은 값을 보낸다.
	 */
	@PostMapping
	public ResponseEntity<ReservationResponse> create(
			@RequestHeader("X-User-Id") String userId,
			@RequestHeader("Idempotency-Key") String idempotencyKey,
			@Valid @RequestBody CreateReservationRequest request) {
		ReservationService.CreateResult result = reservationService.create(userId, idempotencyKey, request);
		HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
		return ResponseEntity.status(status).body(result.reservation());
	}
}
