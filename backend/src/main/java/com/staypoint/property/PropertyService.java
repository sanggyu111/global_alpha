package com.staypoint.property;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.staypoint.common.error.BusinessException;
import com.staypoint.common.error.ErrorCode;
import com.staypoint.inventory.RoomRateRepository;
import com.staypoint.property.AvailabilityRepository.AvailableRoomType;
import com.staypoint.property.PropertyDtos.Availability;
import com.staypoint.property.PropertyDtos.AvailableRoom;
import com.staypoint.property.PropertyDtos.NightlyRate;
import com.staypoint.property.PropertyDtos.PropertyDetail;
import com.staypoint.property.PropertyDtos.PropertySearch;
import com.staypoint.property.PropertyDtos.PropertySummary;
import com.staypoint.property.PropertyDtos.PropertyWithRooms;
import com.staypoint.property.PropertyDtos.RoomOffer;
import com.staypoint.property.PropertyDtos.RoomTypeSummary;

/** 숙소·객실 조회 (FR-SRCH-1~4). 읽기 전용. */
@Service
@Transactional(readOnly = true)
public class PropertyService {

	static final int MAX_NIGHTS = 30;

	private final PropertyRepository propertyRepository;
	private final RoomTypeRepository roomTypeRepository;
	private final AvailabilityRepository availabilityRepository;
	private final RoomRateRepository rateRepository;
	private final Clock clock;

	public PropertyService(PropertyRepository propertyRepository, RoomTypeRepository roomTypeRepository,
			AvailabilityRepository availabilityRepository, RoomRateRepository rateRepository, Clock clock) {
		this.propertyRepository = propertyRepository;
		this.roomTypeRepository = roomTypeRepository;
		this.availabilityRepository = availabilityRepository;
		this.rateRepository = rateRepository;
		this.clock = clock;
	}

	public List<PropertySummary> list(String region) {
		List<Property> properties = (region == null || region.isBlank())
				? propertyRepository.findAllByOrderByIdAsc()
				: propertyRepository.findByRegionOrderByIdAsc(region);
		return properties.stream().map(PropertySummary::from).toList();
	}

	public PropertyDetail detail(Long propertyId) {
		Property p = find(propertyId);
		List<RoomTypeSummary> roomTypes = roomTypeRepository.findByPropertyIdOrderByIdAsc(propertyId).stream()
				.map(RoomTypeSummary::from).toList();
		return new PropertyDetail(p.getId(), p.getName(), p.getAddress(), p.getRegion(), p.getDescription(), roomTypes);
	}

	/** 체크인 ~ 체크아웃 전날 모든 날짜에 예약 가능한 객실 타입과 날짜별 요금·총액. */
	public Availability availability(Long propertyId, LocalDate checkIn, LocalDate checkOut, int guests) {
		find(propertyId);
		long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
		validate(checkIn, checkOut, nights, guests);

		List<AvailableRoom> rooms = availabilityRepository.findAvailable(propertyId, checkIn, checkOut, nights, guests)
				.stream()
				.map(room -> new AvailableRoom(room.roomTypeId(), room.name(), room.capacity(), room.remaining(),
						rateRepository.findForStay(room.roomTypeId(), checkIn, checkOut).stream()
								.map(rate -> new NightlyRate(rate.getStayDate(), rate.getPrice()))
								.toList(),
						room.totalPrice()))
				.toList();
		return new Availability(propertyId, checkIn, checkOut, nights, guests, rooms);
	}

	/**
	 * 숙소 목록 검색 (T17): 지역의 숙소 중 기간 전체에 예약 가능한 객실이 있는 숙소와 그 객실 요약.
	 * 가용 조건은 {@link #availability} 와 같고, 예약 가능한 객실이 0개인 숙소는 결과에서 빠진다.
	 */
	public PropertySearch search(String region, LocalDate checkIn, LocalDate checkOut, int guests) {
		long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
		validate(checkIn, checkOut, nights, guests);
		String regionFilter = (region == null || region.isBlank()) ? null : region;

		// 쿼리 결과는 숙소 id → 총액 순으로 정렬돼 있고, groupingBy 의 toList 는 그 순서를 유지한다
		Map<Long, List<RoomOffer>> roomsByProperty = availabilityRepository
				.findAvailableInRegion(regionFilter, checkIn, checkOut, nights, guests).stream()
				.collect(Collectors.groupingBy(AvailableRoomType::propertyId,
						Collectors.mapping(room -> new RoomOffer(room.roomTypeId(), room.name(), room.capacity(),
								room.remaining(), room.totalPrice()), Collectors.toList())));

		List<PropertyWithRooms> properties = list(regionFilter).stream()
				.filter(p -> roomsByProperty.containsKey(p.id()))
				.map(p -> new PropertyWithRooms(p.id(), p.name(), p.address(), p.region(), roomsByProperty.get(p.id())))
				.toList();
		return new PropertySearch(checkIn, checkOut, nights, guests, properties);
	}

	/** 예약 생성과 같은 기준 (FR-RES-5): 체크인 ≥ 오늘, 체크인 < 체크아웃, 1~30박, 인원 ≥ 1. */
	private void validate(LocalDate checkIn, LocalDate checkOut, long nights, int guests) {
		if (nights < 1) {
			throw invalid("checkOut", "체크아웃은 체크인 다음 날 이후여야 합니다.");
		}
		if (checkIn.isBefore(LocalDate.now(clock))) {
			throw invalid("checkIn", "체크인은 오늘 이후여야 합니다.");
		}
		if (nights > MAX_NIGHTS) {
			throw invalid("checkOut", "최대 " + MAX_NIGHTS + "박까지 조회할 수 있습니다.");
		}
		if (guests < 1) {
			throw invalid("guests", "인원은 1명 이상이어야 합니다.");
		}
	}

	private Property find(Long propertyId) {
		return propertyRepository.findById(propertyId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "숙소를 찾을 수 없습니다.",
						Map.of("propertyId", propertyId)));
	}

	private static BusinessException invalid(String field, String message) {
		return new BusinessException(ErrorCode.VALIDATION_FAILED, message, Map.of(field, message));
	}
}
