package com.staypoint.inventory;

import java.time.LocalDate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomInventoryRepository extends JpaRepository<RoomInventory, Long> {

	/**
	 * 숙박 기간의 모든 날짜 재고를 1실씩 차감한다. 검사(booked_count < total_count)와 차감이 SQL 한 문장이라
	 * 사이에 다른 트랜잭션이 끼어들 틈이 없다. 같은 행을 노리는 트랜잭션은 행 잠금에서 기다렸다가
	 * 앞선 트랜잭션 커밋 후 WHERE 조건을 최신 값으로 다시 평가한다 (설계 4.1).
	 *
	 * @return 차감된 날짜 수. 숙박일 수보다 작으면 매진되었거나 재고 행이 없는 날짜가 있다는 뜻.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			UPDATE room_inventory
			   SET booked_count = booked_count + 1, updated_at = now()
			 WHERE room_type_id = :roomTypeId
			   AND stay_date >= :checkIn AND stay_date < :checkOut
			   AND booked_count < total_count
			""", nativeQuery = true)
	int holdOneRoomPerNight(@Param("roomTypeId") Long roomTypeId,
			@Param("checkIn") LocalDate checkIn,
			@Param("checkOut") LocalDate checkOut);
}
