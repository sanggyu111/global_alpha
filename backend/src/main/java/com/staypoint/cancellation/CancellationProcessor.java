package com.staypoint.cancellation;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.staypoint.common.error.BusinessException;
import com.staypoint.common.error.ErrorCode;
import com.staypoint.inventory.InventoryService;
import com.staypoint.payment.Payment;
import com.staypoint.payment.PaymentCancel;
import com.staypoint.payment.PaymentCancelRepository;
import com.staypoint.payment.PaymentRepository;
import com.staypoint.reservation.Reservation;
import com.staypoint.reservation.ReservationHistory;
import com.staypoint.reservation.ReservationHistoryRepository;
import com.staypoint.reservation.ReservationRepository;
import com.staypoint.reservation.ReservationStatus;

/**
 * 취소 트랜잭션 (설계 4.5). 잠금 순서: reservation → payment → room_inventory (설계 4.3).
 * PG 환불 호출은 하지 않는다 — 환불할 금액을 payment_cancel 에 남기고 커밋한 뒤 CancellationService 가 호출한다.
 */
@Component
class CancellationProcessor {

	private final ReservationRepository reservationRepository;
	private final ReservationHistoryRepository historyRepository;
	private final PaymentRepository paymentRepository;
	private final PaymentCancelRepository cancelRepository;
	private final InventoryService inventoryService;
	private final Clock clock;

	CancellationProcessor(ReservationRepository reservationRepository, ReservationHistoryRepository historyRepository,
			PaymentRepository paymentRepository, PaymentCancelRepository cancelRepository,
			InventoryService inventoryService, Clock clock) {
		this.reservationRepository = reservationRepository;
		this.historyRepository = historyRepository;
		this.paymentRepository = paymentRepository;
		this.cancelRepository = cancelRepository;
		this.inventoryService = inventoryService;
		this.clock = clock;
	}

	/** refundCancelId 가 있으면 커밋 후 PG 환불을 시도해야 한다. */
	record Cancelled(Long refundCancelId) {
	}

	@Transactional
	public Cancelled cancel(Long reservationId, String userId) {
		Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "예약을 찾을 수 없습니다.",
						Map.of("reservationId", reservationId)));
		if (!reservation.getUserId().equals(userId)) {
			throw new BusinessException(ErrorCode.NOT_OWNER);
		}
		// 이미 취소됨 → 멱등 성공. 상태·재고·환불을 다시 건드리지 않는다 (정책 5.3, S9)
		if (reservation.getStatus() == ReservationStatus.CANCELED) {
			return new Cancelled(null);
		}
		if (reservation.getStatus() == ReservationStatus.COMPLETED) {
			throw new BusinessException(ErrorCode.INVALID_STATE, "이용이 끝난 예약은 취소할 수 없습니다.",
					Map.of("status", reservation.getStatus().name()));
		}

		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		BigDecimal refundAmount = BigDecimal.ZERO;
		Long refundCancelId = null;
		if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
			Payment payment = paymentRepository.findApprovedByReservationIdForUpdate(reservationId)
					.orElseThrow(() -> new IllegalStateException("확정 예약에 승인된 결제가 없습니다: " + reservationId));
			LocalDate today = LocalDate.ofInstant(now, clock.getZone());
			refundAmount = RefundPolicy.calculate(payment.getAmount(), reservation.getCheckIn(), today).amount();
			if (refundAmount.signum() > 0) { // 환불 0원(당일·노쇼)이면 PG 호출 없음 (FR-CAN-4)
				refundCancelId = cancelRepository.save(PaymentCancel.userCancel(payment, refundAmount, now)).getId();
			}
		}
		// PENDING(미결제) 취소는 환불 0원 (FR-CAN-5). 진행 중인 결제가 나중에 승인되면 확정 대신 보상 취소된다

		ReservationStatus from = reservation.cancelByUser(refundAmount, now);
		inventoryService.release(reservation.getId(), reservation.getRoomTypeId(),
				reservation.getCheckIn(), reservation.getCheckOut());
		historyRepository.save(ReservationHistory.of(reservation.getId(), from, reservation.getStatus(),
				Reservation.CANCEL_REASON_USER, "user:" + userId, now));
		return new Cancelled(refundCancelId);
	}

	@Transactional(readOnly = true)
	public CancellationResponse currentResult(Long reservationId) {
		Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
		PaymentCancel refund = cancelRepository
				.findByReservationIdAndReason(reservationId, PaymentCancel.Reason.USER_CANCEL)
				.orElse(null);
		return CancellationResponse.of(reservation, refund);
	}
}
