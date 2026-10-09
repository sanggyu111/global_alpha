package com.staypoint.property;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 숙소 조회 API 응답. */
public final class PropertyDtos {

	private PropertyDtos() {
	}

	public record PropertySummary(Long id, String name, String address, String region) {

		static PropertySummary from(Property p) {
			return new PropertySummary(p.getId(), p.getName(), p.getAddress(), p.getRegion());
		}
	}

	public record RoomTypeSummary(Long id, String name, int capacity) {

		static RoomTypeSummary from(RoomType rt) {
			return new RoomTypeSummary(rt.getId(), rt.getName(), rt.getCapacity());
		}
	}

	public record PropertyDetail(Long id, String name, String address, String region, String description,
			List<RoomTypeSummary> roomTypes) {
	}

	public record NightlyRate(LocalDate date, BigDecimal price) {
	}

	/** remaining: 기간 중 가장 적은 날의 잔여 객실 수. totalPrice = 날짜별 1박 요금의 합. */
	public record AvailableRoom(Long roomTypeId, String name, int capacity, int remaining,
			List<NightlyRate> nightlyRates, BigDecimal totalPrice) {
	}

	public record Availability(Long propertyId, LocalDate checkIn, LocalDate checkOut, long nights, int guests,
			List<AvailableRoom> rooms) {
	}
}
