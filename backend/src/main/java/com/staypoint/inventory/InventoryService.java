package com.staypoint.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.staypoint.common.error.BusinessException;
import com.staypoint.common.error.ErrorCode;

/**
 * 날짜별 재고·요금. 예약 트랜잭션 안에서 호출된다 (MANDATORY: 단독으로 차감하는 실수를 막는다).
 */
@Service
public class InventoryService {

	private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

	private final RoomInventoryRepository inventoryRepository;
	private final RoomRateRepository rateRepository;

	public InventoryService(RoomInventoryRepository inventoryRepository, RoomRateRepository rateRepository) {
		this.inventoryRepository = inventoryRepository;
		this.rateRepository = rateRepository;
	}

	/** 숙박 기간 총 요금 = 날짜별 1박 요금의 합. 요금이 없는 날짜가 있으면 예약할 수 없다. */
	@Transactional(readOnly = true)
	public BigDecimal totalPrice(Long roomTypeId, LocalDate checkIn, LocalDate checkOut) {
		List<RoomRate> rates = rateRepository.findForStay(roomTypeId, checkIn, checkOut);
		long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
		if (rates.size() != nights) {
			throw new BusinessException(ErrorCode.RATE_NOT_FOUND, ErrorCode.RATE_NOT_FOUND.defaultMessage(),
					Map.of("nights", nights, "ratedNights", rates.size()));
		}
		return rates.stream().map(RoomRate::getPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	/**
	 * 숙박 기간 모든 날짜의 재고를 1실씩 선점한다. 한 날짜라도 실패하면 예외를 던져
	 * 호출한 트랜잭션 전체가 롤백되므로 일부 날짜만 차감된 채 남지 않는다 (FR-RES-2).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void hold(Long roomTypeId, LocalDate checkIn, LocalDate checkOut) {
		long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
		int held = inventoryRepository.holdOneRoomPerNight(roomTypeId, checkIn, checkOut);
		if (held != nights) {
			throw new BusinessException(ErrorCode.SOLD_OUT);
		}
	}

	/**
	 * 숙박 기간 모든 날짜의 재고를 1실씩 복원한다 (선점 만료 · 취소).
	 * 복원 못 한 날짜가 있으면 재고 데이터가 이미 어긋난 것이므로 예외로 만료·취소를 막지 않고 ERROR 로그를 남긴다.
	 * 예약을 끝내는 것이 우선이고, 어긋난 재고는 정합 점검(설계 6장)으로 찾아 고친다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void release(Long reservationId, Long roomTypeId, LocalDate checkIn, LocalDate checkOut) {
		long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
		int released = inventoryRepository.releaseOneRoomPerNight(roomTypeId, checkIn, checkOut);
		if (released != nights) {
			log.error("재고 복원 불일치: reservationId={}, roomTypeId={}, {}~{}, 복원 {}일 / 숙박 {}일",
					reservationId, roomTypeId, checkIn, checkOut, released, nights);
		}
	}
}
