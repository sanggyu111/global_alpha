package com.staypoint.reservation;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

	Optional<Reservation> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);

	/** 상태를 바꾸기 전에 예약 행을 잠근다 (SELECT … FOR UPDATE). 잠금 순서: reservation → payment → room_inventory. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT r FROM Reservation r WHERE r.id = :id")
	Optional<Reservation> findByIdForUpdate(@Param("id") Long id);

	/**
	 * 선점이 만료된 PENDING 예약 하나를 잠가서 가져온다 (설계 4.7).
	 * FOR UPDATE: 같은 예약을 결제 확정이 동시에 건드리면 둘 중 하나는 기다린다 → 순서가 강제된다.
	 * SKIP LOCKED: 다른 스케줄러(스레드·인스턴스)가 잡고 있는 행은 기다리지 않고 건너뛴다 → 같은 예약을 두 번 만료하지 않는다.
	 * 결제 확정이 잠근 예약도 건너뛰며, 확정이 끝나면 PENDING 이 아니므로 다음 실행에서도 대상이 아니다.
	 */
	@Query(value = """
			SELECT * FROM reservation
			 WHERE status = 'PENDING' AND hold_expires_at <= :now
			 ORDER BY hold_expires_at
			 LIMIT 1
			 FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	Optional<Reservation> lockNextExpiredHold(@Param("now") Instant now);
}
