package com.staypoint.admin;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.staypoint.admin.AdminDtos.AdminReservation;
import com.staypoint.admin.AdminDtos.CalendarDay;
import com.staypoint.admin.AdminDtos.InventoryMismatch;
import com.staypoint.admin.AdminDtos.InventoryRequest;
import com.staypoint.admin.AdminDtos.Page;
import com.staypoint.admin.AdminDtos.PaymentCancelIssue;
import com.staypoint.admin.AdminDtos.PaymentCancelRetryResult;
import com.staypoint.admin.AdminDtos.RateRequest;
import com.staypoint.admin.AdminDtos.RecountResult;
import com.staypoint.payment.PaymentCancel;
import com.staypoint.payment.PaymentCancelRetryService;

import jakarta.validation.Valid;

/** 관리자 API (설계 7장). 인증은 과제 범위 밖 — 실제 서비스라면 이 경로 전체를 관리자 권한으로 막아야 한다. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

	private final AdminReservationService reservationService;
	private final AdminInventoryService inventoryService;
	private final AdminIssueService issueService;
	private final PaymentCancelRetryService cancelRetryService;
	private final Clock clock;

	public AdminController(AdminReservationService reservationService, AdminInventoryService inventoryService,
			AdminIssueService issueService, PaymentCancelRetryService cancelRetryService, Clock clock) {
		this.reservationService = reservationService;
		this.inventoryService = inventoryService;
		this.issueService = issueService;
		this.cancelRetryService = cancelRetryService;
		this.clock = clock;
	}

	/** 예약 목록. from · to 는 체크인 날짜 기준(양 끝 포함), page 는 0 부터. */
	@GetMapping("/reservations")
	public Page<AdminReservation> reservations(@RequestParam(required = false) String status,
			@RequestParam(required = false) Long propertyId,
			@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return reservationService.search(status, propertyId, from, to, page, size);
	}

	@GetMapping("/room-types/{roomTypeId}/calendar")
	public List<CalendarDay> calendar(@PathVariable Long roomTypeId,
			@RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
		return inventoryService.calendar(roomTypeId, from, to);
	}

	/** 기간 재고 일괄 설정. 예약된 수보다 줄이려는 날짜가 있으면 409 INVENTORY_BELOW_BOOKED (details.dates). */
	@PutMapping("/room-types/{roomTypeId}/inventory")
	public List<CalendarDay> setInventory(@PathVariable Long roomTypeId, @Valid @RequestBody InventoryRequest request) {
		return inventoryService.setInventory(roomTypeId, request.from(), request.to(), request.totalCount());
	}

	@PutMapping("/room-types/{roomTypeId}/rates")
	public List<CalendarDay> setRates(@PathVariable Long roomTypeId, @Valid @RequestBody RateRequest request) {
		return inventoryService.setRates(roomTypeId, request.from(), request.to(), request.price());
	}

	/** 운영자 확인 대상. 기본은 MANUAL_REVIEW (재시도 소진 · PG 거절). */
	@GetMapping("/payment-cancels")
	public List<PaymentCancelIssue> paymentCancels(@RequestParam(defaultValue = "MANUAL_REVIEW") String status) {
		return issueService.paymentCancels(status);
	}

	/**
	 * 운영자 수동 재시도: PG 쪽 원인을 확인한 뒤 MANUAL_REVIEW 건을 한 번 더 시도한다.
	 * 실패하면 다시 MANUAL_REVIEW 로 돌아간다 (자동 재시도는 다시 시작되지 않음).
	 */
	@PostMapping("/payment-cancels/{cancelId}/retry")
	public PaymentCancelRetryResult retryPaymentCancel(@PathVariable Long cancelId) {
		PaymentCancel cancel = cancelRetryService.retryManually(cancelId);
		return new PaymentCancelRetryResult(cancel.getId(), cancel.getStatus().name(), cancel.getAttemptCount(),
				cancel.getLastError());
	}

	/** 재고 불일치 감지. 기간을 안 주면 오늘부터 1년. */
	@GetMapping("/inventory/mismatches")
	public List<InventoryMismatch> mismatches(
			@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
		LocalDate start = from != null ? from : LocalDate.now(clock);
		LocalDate end = to != null ? to : start.plusDays(AdminRanges.MAX_DAYS - 1);
		return inventoryService.mismatches(start, end);
	}

	/** 재고 복구: 해당 날짜 booked_count 를 활성 예약 수로 맞춘다. */
	@PostMapping("/room-types/{roomTypeId}/inventory/{stayDate}/recount")
	public RecountResult recount(@PathVariable Long roomTypeId,
			@PathVariable @DateTimeFormat(iso = ISO.DATE) LocalDate stayDate) {
		return inventoryService.recount(roomTypeId, stayDate);
	}
}
