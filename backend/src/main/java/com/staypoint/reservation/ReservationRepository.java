package com.staypoint.reservation;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

	Optional<Reservation> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);
}
