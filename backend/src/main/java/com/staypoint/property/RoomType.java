package com.staypoint.property;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 객실 타입. 예약 생성에서는 정원(capacity) 검증에만 쓴다.
 * 조회 API(T09)에서 필요한 컬럼은 그때 추가한다.
 */
@Entity
@Table(name = "room_type")
public class RoomType {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "property_id", nullable = false)
	private Long propertyId;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private int capacity;

	protected RoomType() {
	}

	public Long getId() {
		return id;
	}

	public Long getPropertyId() {
		return propertyId;
	}

	public String getName() {
		return name;
	}

	public int getCapacity() {
		return capacity;
	}
}
