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

	/** 숙소 목록 검색용 객실 요약 (T17). 날짜별 요금은 상세 화면에서만 보여준다. */
	public record RoomOffer(Long roomTypeId, String name, int capacity, int remaining, BigDecimal totalPrice) {
	}

	public record PropertyWithRooms(Long id, String name, String address, String region, List<RoomOffer> rooms) {
	}

	/** properties: 예약 가능한 객실이 1개 이상인 숙소만 (id 순). */
	public record PropertySearch(LocalDate checkIn, LocalDate checkOut, long nights, int guests,
			List<PropertyWithRooms> properties) {
	}
}
