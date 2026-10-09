package com.staypoint.payment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.staypoint.common.error.BusinessException;
import com.staypoint.common.error.ErrorCode;
import com.staypoint.payment.PgClient.PgApproval;
import com.staypoint.reservation.Reservation;
import com.staypoint.reservation.ReservationHistory;
import com.staypoint.reservation.ReservationHistoryRepository;
import com.staypoint.reservation.ReservationRepository;
import com.staypoint.reservation.ReservationStatus;

/**
 * 결제의 DB 트랜잭션 부분 (설계 4.4). PG 호출은 이 클래스 밖(PaymentService)에서 트랜잭션 사이에 한다.
 * 잠금 순서는 모든 흐름에서 reservation → payment (설계 4.3) — 교착을 피하기 위함.
 */
@Component
class PaymentProcessor {

	private static final Logger log = LoggerFactory.getLogger(PaymentProcessor.class);

	private final ReservationRepository reservationRepository;
	private final ReservationHistoryRepository historyRepository;
	private final PaymentRepository paymentRepository;
	private final PaymentCancelRepository cancelRepository;
	private final Clock clock;

	PaymentProcessor(ReservationRepository reservationRepository, ReservationHistoryRepository historyRepository,
			PaymentRepository paymentRepository, PaymentCancelRepository cancelRepository, Clock clock) {
		this.reservationRepository = reservationRepository;
		this.historyRepository = historyRepository;
		this.paymentRepository = paymentRepository;
		this.cancelRepository = cancelRepository;
		this.clock = clock;
	}

	/** needsPgCall=false 면 같은 결제 키의 결과가 이미 정해져 있어 PG 를 부를 필요가 없다. */
	record Prepared(Long paymentId, String orderId, BigDecimal amount, boolean needsPgCall) {
	}

	/** applyResult 결과. compensationCancelId 가 있으면 커밋 후 PG 취소를 시도해야 한다. */
	record Applied(Long compensationCancelId) {
	}

	/**
	 * TX1: 예약을 잠그고 검증한 뒤 READY 결제를 만든다.
	 * 같은 예약에 대한 결제 요청은 예약 행 잠금에서 줄을 서므로, 다른 키로 연타해도 두 번째는
	 * 앞의 READY 결제를 보고 PAYMENT_IN_PROGRESS 로 거부된다 (최종 방어선은 부분 UNIQUE 인덱스).
	 */
	@Transactional
	public Prepared prepare(Long reservationId, String userId, String idempotencyKey) {
		Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "예약을 찾을 수 없습니다.",
						Map.of("reservationId", reservationId)));
		if (!reservation.getUserId().equals(userId)) {
			throw new BusinessException(ErrorCode.NOT_OWNER);
		}

		// 같은 결제 키 재요청 (연타 · 타임아웃 후 재시도): 새 결제를 만들지 않는다.
		// 아직 READY 면 같은 orderId 로 PG 를 다시 불러 결과를 받는다 (PG 가 처음 결과를 돌려줌).
		Optional<Payment> existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
		if (existing.isPresent()) {
			Payment p = existing.get();
			if (!p.getReservationId().equals(reservationId)) {
				throw new BusinessException(ErrorCode.VALIDATION_FAILED, "다른 예약에 사용된 Idempotency-Key 입니다.",
						Map.of("header", "Idempotency-Key"));
			}
			return new Prepared(p.getId(), p.getPgOrderId(), p.getAmount(), p.getStatus() == PaymentStatus.READY);
		}

		Instant now = now();
		reservation.assertPayable(now); // S4: 만료됐거나 30초 이하로 남았으면 HOLD_EXPIRED
		if (paymentRepository.existsByReservationIdAndStatusIn(reservationId,
				List.of(PaymentStatus.READY, PaymentStatus.APPROVED))) {
			throw new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS);
		}
		String orderId = "P" + UUID.randomUUID().toString().replace("-", "");
		Payment payment = paymentRepository.saveAndFlush(
				Payment.ready(reservationId, orderId, reservation.getTotalAmount(), idempotencyKey, now));
		return new Prepared(payment.getId(), orderId, payment.getAmount(), true);
	}

	/**
	 * TX2: PG 결과 반영 — 동기 응답 · 웹훅 · 상태 확정 스케줄러가 모두 이 한 경로를 쓴다.
	 * 결제가 READY 가 아니면 이미 반영된 것이므로 아무것도 하지 않는다 → 같은 통지가 여러 번 와도 한 번만 반영.
	 */
	@Transactional
	public Applied applyResult(String orderId, PgApproval result, String actor) {
		Long reservationId = paymentRepository.findReservationIdByPgOrderId(orderId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "결제를 찾을 수 없습니다.",
						Map.of("orderId", orderId)));
		Reservation reservation = reservationRepository.findByIdForUpdate(reservationId).orElseThrow();
		Payment payment = paymentRepository.findByPgOrderIdForUpdate(orderId).orElseThrow();

		if (payment.getStatus() != PaymentStatus.READY) {
			return new Applied(null); // 중복 통지 · 이미 반영됨
		}
		Instant now = now();
		if (!result.approved()) {
			payment.fail(result.failReason(), now); // 예약은 PENDING 유지 → 만료 전 재결제 가능 (정책 5.4)
			return new Applied(null);
		}

		payment.approve(result.tid(), now);
		String notConfirmable = notConfirmableReason(reservation, payment, result, now);
		if (notConfirmable == null) {
			ReservationStatus from = reservation.confirm(now);
			historyRepository.save(ReservationHistory.of(reservation.getId(), from, reservation.getStatus(),
					"PAYMENT_APPROVED", actor, now));
			return new Applied(null);
		}

		// 승인은 됐는데 확정할 수 없다 → 결제가 그대로 남지 않도록 같은 트랜잭션에서 보상 취소 요청을 남긴다 (S7)
		payment.markCompensation(notConfirmable, now);
		PaymentCancel compensation = cancelRepository.save(PaymentCancel.compensation(payment, now));
		log.warn("결제 승인 후 확정 실패 → 보상 취소 요청: orderId={}, reservationId={}, 사유={}",
				orderId, reservationId, notConfirmable);
		return new Applied(compensation.getId());
	}

	/** 확정할 수 없는 이유. 확정 가능하면 null (FR-PAY-7~9). */
	private static String notConfirmableReason(Reservation reservation, Payment payment, PgApproval result,
			Instant now) {
		if (reservation.getStatus() != ReservationStatus.PENDING) {
			return "RESERVATION_" + reservation.getStatus(); // 만료 처리됨 · 취소됨
		}
		if (!reservation.isConfirmable(now)) {
			return "HOLD_EXPIRED"; // 만료 시각은 지났지만 스케줄러가 아직 처리 전
		}
		if (result.amount() == null || result.amount().compareTo(payment.getAmount()) != 0
				|| payment.getAmount().compareTo(reservation.getTotalAmount()) != 0) {
			return "AMOUNT_MISMATCH";
		}
		return null;
	}

	/** 결제 요청에 돌려줄 현재 상태. */
	@Transactional(readOnly = true)
	public PaymentResponse currentResult(Long paymentId) {
		Payment payment = paymentRepository.findById(paymentId).orElseThrow();
		Reservation reservation = reservationRepository.findById(payment.getReservationId()).orElseThrow();
		boolean compensated = cancelRepository.existsByPaymentIdAndReason(paymentId, PaymentCancel.Reason.COMPENSATION);
		return PaymentResponse.of(payment, reservation, compensated);
	}

	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MICROS);
	}
}
