package com.staypoint.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 객실 타입 × 날짜별 1박 요금. 주말·성수기 요금은 날짜마다 다른 price 로 표현한다.
 * 통화는 KRW 고정이라 currency 컬럼은 매핑하지 않는다 (기획 5.2).
 */
@Entity
@Table(name = "room_rate")
public class RoomRate {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "room_type_id", nullable = false)
	private Long roomTypeId;

	@Column(name = "stay_date", nullable = false)
	private LocalDate stayDate;

	@Column(nullable = false, precision = 12, scale = 0)
	private BigDecimal price;

	protected RoomRate() {
	}

	public Long getRoomTypeId() {
		return roomTypeId;
	}

	public LocalDate getStayDate() {
		return stayDate;
	}

	public BigDecimal getPrice() {
		return price;
	}
}
