package com.staypoint.reservation;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.staypoint.common.StaypointProperties;
import com.staypoint.inventory.InventoryService;
import com.staypoint.reservation.dto.CreateReservationRequest;

/**
 * 예약 생성 트랜잭션 (설계 4.2). ReservationService 와 분리한 이유는 ReservationService 의 주석 참고.
 *
 * <p>순서: 예약 INSERT → 재고 조건부 UPDATE → 이력 INSERT.
 * 예약 INSERT 를 먼저 하는 이유: 같은 멱등 키의 중복 요청은 UNIQUE 인덱스에서 먼저 막혀
 * 재고 행 잠금까지 가지 않는다. 재고 차감이 실패하면 예약 INSERT 도 함께 롤백된다.
 */
@Component
class ReservationCreator {

	private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyMMdd");

	private final ReservationRepository reservationRepository;
	private final ReservationHistoryRepository historyRepository;
	private final InventoryService inventoryService;
	private final StaypointProperties properties;
	private final Clock clock;

	ReservationCreator(ReservationRepository reservationRepository,
			ReservationHistoryRepository historyRepository,
			InventoryService inventoryService,
			StaypointProperties properties,
			Clock clock) {
		this.reservationRepository = reservationRepository;
		this.historyRepository = historyRepository;
		this.inventoryService = inventoryService;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional
	public Reservation create(String userId, String idempotencyKey, CreateReservationRequest request) {
		BigDecimal totalAmount = inventoryService.totalPrice(request.roomTypeId(), request.checkIn(), request.checkOut());
		// timestamptz 는 마이크로초까지만 저장한다. 미리 잘라 두어야 첫 응답과 멱등 재요청 응답(DB 값)이 같다
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

		Reservation reservation = Reservation.hold(newReservationNo(), userId, idempotencyKey,
				request.roomTypeId(), request.checkIn(), request.checkOut(), request.guestCount(),
				request.guestName(), request.guestPhone(), totalAmount,
				now, now.plus(properties.holdMinutes(), ChronoUnit.MINUTES));
		// IDENTITY 전략이라 즉시 INSERT 된다. 같은 (user_id, idempotency_key) 가 있으면 여기서 UNIQUE 위반
		reservationRepository.saveAndFlush(reservation);

		inventoryService.hold(request.roomTypeId(), request.checkIn(), request.checkOut());

		historyRepository.save(ReservationHistory.of(reservation.getId(), null, ReservationStatus.PENDING,
				"CREATED", "user:" + userId, now));
		return reservation;
	}

	/** 예: R261009-3F9A1C2E. 사람이 읽기 쉬운 날짜 + 랜덤 8자리. 충돌하면 UNIQUE(reservation_no) 가 막는다. */
	private String newReservationNo() {
		String random = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
		return "R" + LocalDate.now(clock).format(NO_DATE) + "-" + random;
	}
}
