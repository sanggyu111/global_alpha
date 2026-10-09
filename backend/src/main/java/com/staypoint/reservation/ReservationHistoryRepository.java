package com.staypoint.reservation;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationHistoryRepository extends JpaRepository<ReservationHistory, Long> {

	List<ReservationHistory> findByReservationIdOrderByIdAsc(Long reservationId);
}
