package com.staypoint.mypage;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.staypoint.cancellation.RefundPolicy;
import com.staypoint.common.error.BusinessException;
import com.staypoint.common.error.ErrorCode;
import com.staypoint.mypage.MyReservationDtos.PaymentSummary;
import com.staypoint.mypage.MyReservationDtos.RefundEstimate;
import com.staypoint.mypage.MyReservationDtos.ReservationDetail;
import com.staypoint.mypage.MyReservationDtos.ReservationSummary;
import com.staypoint.payment.Payment;
import com.staypoint.payment.PaymentCancel;
import com.staypoint.payment.PaymentCancelRepository;
import com.staypoint.payment.PaymentRepository;
import com.staypoint.payment.PaymentStatus;
import com.staypoint.property.Property;
import com.staypoint.property.PropertyRepository;
import com.staypoint.property.RoomType;
import com.staypoint.property.RoomTypeRepository;
import com.staypoint.reservation.Reservation;
import com.staypoint.reservation.ReservationRepository;
import com.staypoint.reservation.ReservationStatus;

/**
 * 내 예약 목록·상세 (FR-UI-2). 예약·결제·환불 정책을 함께 읽는 조회 전용이라 별도 패키지에 둔다
 * (reservation 이 payment·cancellation 에 의존하면 순환이 생김, L035).
 */
@Service
@Transactional(readOnly = true)
public class MyReservationService {

	private final ReservationRepository reservationRepository;
	private final RoomTypeRepository roomTypeRepository;
	private final PropertyRepository propertyRepository;
	private final PaymentRepository paymentRepository;
	private final PaymentCancelRepository cancelRepository;
	private final Clock clock;

	public MyReservationService(ReservationRepository reservationRepository, RoomTypeRepository roomTypeRepository,
			PropertyRepository propertyRepository, PaymentRepository paymentRepository,
			PaymentCancelRepository cancelRepository, Clock clock) {
		this.reservationRepository = reservationRepository;
		this.roomTypeRepository = roomTypeRepository;
		this.propertyRepository = propertyRepository;
		this.paymentRepository = paymentRepository;
		this.cancelRepository = cancelRepository;
		this.clock = clock;
	}

	public List<ReservationSummary> list(String userId) {
		List<Reservation> reservations = reservationRepository.findTop100ByUserIdOrderByCreatedAtDesc(userId);
		// 객실 타입·숙소 이름은 예약마다 조회하지 않고 한 번에 모아 읽는다 (N+1 방지)
		Map<Long, RoomType> roomTypes = byId(roomTypeRepository.findAllById(
				reservations.stream().map(Reservation::getRoomTypeId).collect(Collectors.toSet())), RoomType::getId);
		Set<Long> propertyIds = roomTypes.values().stream().map(RoomType::getPropertyId).collect(Collectors.toSet());
		Map<Long, Property> properties = byId(propertyRepository.findAllById(propertyIds), Property::getId);

		return reservations.stream().map(r -> {
			RoomType rt = roomTypes.get(r.getRoomTypeId());
			return new ReservationSummary(r.getId(), r.getReservationNo(), r.getStatus(),
					properties.get(rt.getPropertyId()).getName(), rt.getName(),
					r.getCheckIn(), r.getCheckOut(), r.getTotalAmount(), r.getCreatedAt());
		}).toList();
	}

	public ReservationDetail detail(Long reservationId, String userId) {
		Reservation r = reservationRepository.findById(reservationId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "예약을 찾을 수 없습니다.",
						Map.of("reservationId", reservationId)));
		if (!r.getUserId().equals(userId)) {
			throw new BusinessException(ErrorCode.NOT_OWNER);
		}
		RoomType rt = roomTypeRepository.findById(r.getRoomTypeId()).orElseThrow();
		Property property = propertyRepository.findById(rt.getPropertyId()).orElseThrow();
		Payment payment = paymentRepository.findFirstByReservationIdOrderByIdDesc(reservationId).orElse(null);
		String refundStatus = cancelRepository.findByReservationIdAndReason(reservationId, PaymentCancel.Reason.USER_CANCEL)
				.map(c -> c.getStatus().name())
				.orElse(r.getStatus() == ReservationStatus.CANCELED ? "NONE" : null);

		return new ReservationDetail(r.getId(), r.getReservationNo(), r.getStatus(),
				property.getId(), property.getName(), rt.getId(), rt.getName(),
				r.getCheckIn(), r.getCheckOut(), ChronoUnit.DAYS.between(r.getCheckIn(), r.getCheckOut()),
				r.getGuestCount(), r.getGuestName(), r.getTotalAmount(), r.getHoldExpiresAt(), r.getConfirmedAt(),
				r.getCanceledAt(), r.getCancelReason(), r.getRefundAmount(), refundStatus,
				payment == null ? null : new PaymentSummary(payment.getId(), payment.getStatus(), payment.getAmount(),
						payment.getCanceledAmount(), payment.getFailReason()),
				refundEstimate(r, payment));
	}

	/** 확정 예약을 "지금" 취소하면 받을 환불액. 취소 API 와 같은 RefundPolicy · 같은 기준일(Asia/Seoul 오늘)을 쓴다. */
	private RefundEstimate refundEstimate(Reservation r, Payment payment) {
		if (r.getStatus() != ReservationStatus.CONFIRMED || payment == null
				|| payment.getStatus() != PaymentStatus.APPROVED) {
			return null;
		}
		RefundPolicy.Refund refund = RefundPolicy.calculate(payment.getAmount(), r.getCheckIn(), LocalDate.now(clock));
		return new RefundEstimate(refund.daysBeforeCheckIn(), refund.percent(), refund.amount());
	}

	private static <T> Map<Long, T> byId(List<T> items, Function<T, Long> id) {
		return items.stream().collect(Collectors.toMap(id, Function.identity()));
	}
}
