package com.staypoint.mypage;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.staypoint.mypage.MyReservationDtos.ReservationDetail;
import com.staypoint.mypage.MyReservationDtos.ReservationSummary;

@RestController
public class MyReservationController {

	private final MyReservationService myReservationService;

	public MyReservationController(MyReservationService myReservationService) {
		this.myReservationService = myReservationService;
	}

	@GetMapping("/api/reservations/me")
	public List<ReservationSummary> list(@RequestHeader("X-User-Id") String userId) {
		return myReservationService.list(userId);
	}

	/** 본인 예약만 조회할 수 있다. 확정 예약은 "지금 취소하면" 환불 예정액을 함께 준다. */
	@GetMapping("/api/reservations/{reservationId}")
	public ReservationDetail detail(@PathVariable Long reservationId, @RequestHeader("X-User-Id") String userId) {
		return myReservationService.detail(reservationId, userId);
	}
}
