package com.staypoint.inventory;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 객실 타입 × 날짜별 재고. booked_count 변경은 엔티티 수정이 아니라
 * RoomInventoryRepository 의 조건부 UPDATE 로만 한다 (설계 4.1).
 */
@Entity
@Table(name = "room_inventory")
public class RoomInventory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "room_type_id", nullable = false)
	private Long roomTypeId;

	@Column(name = "stay_date", nullable = false)
	private LocalDate stayDate;

	@Column(name = "total_count", nullable = false)
	private int totalCount;

	@Column(name = "booked_count", nullable = false)
	private int bookedCount;

	protected RoomInventory() {
	}

	public Long getId() {
		return id;
	}

	public Long getRoomTypeId() {
		return roomTypeId;
	}

	public LocalDate getStayDate() {
		return stayDate;
	}

	public int getTotalCount() {
		return totalCount;
	}

	public int getBookedCount() {
		return bookedCount;
	}
}
