package com.staypoint.reservation;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.staypoint.inventory.InventoryService;

/**
 * 선점 만료 처리 (설계 4.7, FR-RES-4). 예약 1건 = 트랜잭션 1개.
 * 한 건이 실패해도 이미 처리한 건은 커밋되어 있고, 잠금을 오래 쥐지 않는다.
 * 잠금 순서는 다른 흐름과 같게 reservation → room_inventory (설계 4.3).
 */
@Service
public class HoldExpiryService {

	static final String ACTOR = "system:hold-expiry";

	private final ReservationRepository reservationRepository;
	private final ReservationHistoryRepository historyRepository;
	private final InventoryService inventoryService;
	private final Clock clock;

	public HoldExpiryService(ReservationRepository reservationRepository,
			ReservationHistoryRepository historyRepository,
			InventoryService inventoryService,
			Clock clock) {
		this.reservationRepository = reservationRepository;
		this.historyRepository = historyRepository;
		this.inventoryService = inventoryService;
		this.clock = clock;
	}

	/** 만료된 예약 하나를 처리한다. 처리할 예약이 없으면 false. */
	@Transactional
	public boolean expireNext() {
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		Optional<Reservation> found = reservationRepository.lockNextExpiredHold(now);
		if (found.isEmpty()) {
			return false;
		}
		Reservation reservation = found.get();
		ReservationStatus from = reservation.expire(now);
		inventoryService.release(reservation.getId(), reservation.getRoomTypeId(),
				reservation.getCheckIn(), reservation.getCheckOut());
		historyRepository.save(ReservationHistory.of(reservation.getId(), from, reservation.getStatus(),
				Reservation.CANCEL_REASON_HOLD_EXPIRED, ACTOR, now));
		return true;
	}
}
