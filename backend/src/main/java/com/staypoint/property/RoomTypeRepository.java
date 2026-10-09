package com.staypoint.property;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomTypeRepository extends JpaRepository<RoomType, Long> {

	List<RoomType> findByPropertyIdOrderByIdAsc(Long propertyId);
}
