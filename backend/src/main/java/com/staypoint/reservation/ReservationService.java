package com.staypoint.reservation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.staypoint.common.error.BusinessException;
import com.staypoint.common.error.ErrorCode;
import com.staypoint.property.RoomType;
import com.staypoint.property.RoomTypeRepository;
import com.staypoint.reservation.dto.CreateReservationRequest;
import com.staypoint.reservation.dto.ReservationResponse;

/**
 * 예약 생성의 입구. 입력 검증과 멱등 처리를 맡고, 실제 생성은 ReservationCreator 의 트랜잭션에서 한다.
 *
 * <p>이 클래스에 @Transactional 을 걸지 않는 이유: 같은 멱등 키의 동시 요청은 UNIQUE 위반으로 끝나는데,
 * PostgreSQL 은 오류가 난 트랜잭션 안에서 더 이상 쿼리를 실행할 수 없다. 그래서 생성 트랜잭션을 먼저
 * 롤백시킨 뒤, 바깥(트랜잭션 없음)에서 먼저 커밋된 예약을 다시 조회해 돌려준다.
 */
@Service
public class ReservationService {

	static final int MAX_NIGHTS = 30;
	private static final String IDEMPOTENCY_CONSTRAINT = "uq_reservation_idempotency";

	private final ReservationCreator creator;
	private final ReservationRepository reservationRepository;
	private final RoomTypeRepository roomTypeRepository;
	private final Clock clock;

	public ReservationService(ReservationCreator creator, ReservationRepository reservationRepository,
			RoomTypeRepository roomTypeRepository, Clock clock) {
		this.creator = creator;
		this.reservationRepository = reservationRepository;
		this.roomTypeRepository = roomTypeRepository;
		this.clock = clock;
	}

	/** created=false 면 같은 멱등 키로 이미 만들어진 예약을 그대로 돌려준 것 (FR-RES-6). */
	public record CreateResult(ReservationResponse reservation, boolean created) {
	}

	public CreateResult create(String userId, String idempotencyKey, CreateReservationRequest request) {
		requireLength("X-User-Id", userId, 50);
		requireLength("Idempotency-Key", idempotencyKey, 100);

		// 재전송이면 검증(예: 그 사이 체크인 날짜가 지남)과 무관하게 처음 결과를 돌려준다
		Optional<Reservation> existing = reservationRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
		if (existing.isPresent()) {
			return replay(existing.get());
		}

		validate(request);
		try {
			Reservation created = creator.create(userId, idempotencyKey, request);
			return new CreateResult(ReservationResponse.from(created), true);
		} catch (DataIntegrityViolationException e) {
			if (!isIdempotencyConflict(e)) {
				throw e;
			}
			// 같은 키의 동시 요청이 먼저 커밋됨 (위 조회와 INSERT 사이에 끼어든 경우) → 그 예약을 돌려준다
			return replay(reservationRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
					.orElseThrow(() -> e));
		}
	}

	private CreateResult replay(Reservation reservation) {
		return new CreateResult(ReservationResponse.from(reservation), false);
	}

	/** FR-RES-5: 체크인 < 체크아웃, 체크인 ≥ 오늘(Asia/Seoul), 1~30박, 인원 ≤ 객실 정원. */
	private void validate(CreateReservationRequest request) {
		LocalDate today = LocalDate.now(clock);
		if (!request.checkOut().isAfter(request.checkIn())) {
			throw invalid("checkOut", "체크아웃은 체크인 다음 날 이후여야 합니다.");
		}
		if (request.checkIn().isBefore(today)) {
			throw invalid("checkIn", "체크인은 오늘 이후여야 합니다.");
		}
		if (ChronoUnit.DAYS.between(request.checkIn(), request.checkOut()) > MAX_NIGHTS) {
			throw invalid("checkOut", "최대 " + MAX_NIGHTS + "박까지 예약할 수 있습니다.");
		}
		RoomType roomType = roomTypeRepository.findById(request.roomTypeId())
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "객실 타입을 찾을 수 없습니다.",
						Map.of("roomTypeId", request.roomTypeId())));
		if (request.guestCount() > roomType.getCapacity()) {
			throw invalid("guestCount", "객실 정원(" + roomType.getCapacity() + "명)을 초과했습니다.");
		}
	}

	private static void requireLength(String header, String value, int max) {
		if (value == null || value.isBlank() || value.length() > max) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED, header + " 헤더는 1~" + max + "자여야 합니다.",
					Map.of("header", header));
		}
	}

	private static BusinessException invalid(String field, String message) {
		return new BusinessException(ErrorCode.VALIDATION_FAILED, message, Map.of(field, message));
	}

	private static boolean isIdempotencyConflict(DataIntegrityViolationException e) {
		String message = e.getMostSpecificCause().getMessage();
		return message != null && message.contains(IDEMPOTENCY_CONSTRAINT);
	}
}
