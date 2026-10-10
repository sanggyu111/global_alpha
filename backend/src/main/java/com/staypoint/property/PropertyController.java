package com.staypoint.property;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.staypoint.property.PropertyDtos.Availability;
import com.staypoint.property.PropertyDtos.PropertyDetail;
import com.staypoint.property.PropertyDtos.PropertySearch;
import com.staypoint.property.PropertyDtos.PropertySummary;

@RestController
@RequestMapping("/api/properties")
public class PropertyController {

	private final PropertyService propertyService;

	public PropertyController(PropertyService propertyService) {
		this.propertyService = propertyService;
	}

	@GetMapping
	public List<PropertySummary> list(@RequestParam(required = false) String region) {
		return propertyService.list(region);
	}

	/** 숙소 목록 화면 검색 (T17). 예약 가능한 객실이 있는 숙소만, 숙소마다 객실 요약을 붙여 돌려준다. */
	@GetMapping("/search")
	public PropertySearch search(@RequestParam(required = false) String region,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut,
			@RequestParam int guests) {
		return propertyService.search(region, checkIn, checkOut, guests);
	}

	@GetMapping("/{propertyId}")
	public PropertyDetail detail(@PathVariable Long propertyId) {
		return propertyService.detail(propertyId);
	}

	/** 예약 가능한 객실만 돌려준다. 날짜 형식: yyyy-MM-dd. */
	@GetMapping("/{propertyId}/availability")
	public Availability availability(@PathVariable Long propertyId,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut,
			@RequestParam int guests) {
		return propertyService.availability(propertyId, checkIn, checkOut, guests);
	}
}
