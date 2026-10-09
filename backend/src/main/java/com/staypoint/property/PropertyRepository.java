package com.staypoint.property;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PropertyRepository extends JpaRepository<Property, Long> {

	List<Property> findAllByOrderByIdAsc();

	List<Property> findByRegionOrderByIdAsc(String region);
}
