package com.staypoint.cancellation;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.staypoint.payment.PgClient;

@RestController
public class CancellationController {

	private final CancellationService cancellationService;

	public CancellationController(CancellationService cancellationService) {
		this.cancellationService = cancellationService;
	}

	/**
	 * 예약 취소. 이미 취소된 예약을 다시 취소하면 처음 취소 결과를 200 으로 돌려준다 (멱등).
	 * 별도 Idempotency-Key 가 없어도 되는 이유: "이 예약을 취소" 는 몇 번 해도 결과가 같은 요청이고,
	 * 예약 행 잠금 + CANCELED 상태 확인이 중복 처리를 막는다.
	 * failTimes·failType 은 데모용 환불 장애 주입 값 (T20): 모의 PG 가 이 결제의 환불을 failTimes 번 실패시킨다.
	 */
	@PostMapping("/api/reservations/{reservationId}/cancel")
	public CancellationResponse cancel(@PathVariable Long reservationId, @RequestHeader("X-User-Id") String userId,
			@RequestParam(required = false) Integer failTimes,
			@RequestParam(defaultValue = "UNAVAILABLE") PgClient.CancelFaultType failType) {
		PgClient.CancelFault refundFault = failTimes == null ? null : new PgClient.CancelFault(failTimes, failType);
		return cancellationService.cancel(reservationId, userId, refundFault);
	}
}
