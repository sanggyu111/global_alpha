package com.staypoint.inventory;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomRateRepository extends JpaRepository<RoomRate, Long> {

	/** 숙박일(체크인 ~ 체크아웃 전날)의 요금. 체크아웃 날짜는 포함하지 않는다. */
	@Query("""
			SELECT r FROM RoomRate r
			 WHERE r.roomTypeId = :roomTypeId
			   AND r.stayDate >= :checkIn AND r.stayDate < :checkOut
			 ORDER BY r.stayDate
			""")
	List<RoomRate> findForStay(@Param("roomTypeId") Long roomTypeId,
			@Param("checkIn") LocalDate checkIn,
			@Param("checkOut") LocalDate checkOut);
}
