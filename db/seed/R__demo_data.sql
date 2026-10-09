-- 데모 데이터 (Flyway repeatable migration — `seed` 프로파일에서만 적용, 설계 3.3)
-- 여러 번 실행돼도 중복이 생기지 않도록 모두 "없을 때만 넣기" 로 작성한다.
-- 재고·요금은 이 파일이 처음 적용된 날부터 90일치. 이후 기간은 관리자 화면에서 추가한다.

-- 숙소 3개
INSERT INTO property (name, address, region, description)
SELECT v.name, v.address, v.region, v.description
FROM (VALUES
    ('스테이포인트 서울 시청', '서울특별시 중구 세종대로 110', '서울', '시청역 도보 3분, 비즈니스 여행객을 위한 시티 호텔'),
    ('스테이포인트 부산 해운대', '부산광역시 해운대구 해운대해변로 264', '부산', '해운대 해변 앞 오션뷰 리조트'),
    ('스테이포인트 제주 애월', '제주특별자치도 제주시 애월읍 애월해안로 272', '제주', '애월 해안도로의 소규모 부티크 스테이')
) AS v(name, address, region, description)
WHERE NOT EXISTS (SELECT 1 FROM property p WHERE p.name = v.name);

-- 객실 타입 6개 (마지막 1실 동시 예약 데모용으로 객실 1개짜리 타입 포함)
INSERT INTO room_type (property_id, name, capacity, default_total_rooms)
SELECT p.id, v.room_name, v.capacity, v.total_rooms
FROM (VALUES
    ('스테이포인트 서울 시청', '스탠다드 더블', 2, 10),
    ('스테이포인트 서울 시청', '디럭스 트윈', 3, 5),
    ('스테이포인트 부산 해운대', '오션뷰 디럭스', 2, 8),
    ('스테이포인트 부산 해운대', '패밀리 스위트', 4, 3),
    ('스테이포인트 제주 애월', '가든 스튜디오', 2, 4),
    ('스테이포인트 제주 애월', '독채 풀빌라', 6, 1)
) AS v(property_name, room_name, capacity, total_rooms)
JOIN property p ON p.name = v.property_name
WHERE NOT EXISTS (
    SELECT 1 FROM room_type rt WHERE rt.property_id = p.id AND rt.name = v.room_name
);

-- 날짜별 재고: 오늘부터 90일, 객실 타입 기본 객실 수
INSERT INTO room_inventory (room_type_id, stay_date, total_count, booked_count)
SELECT rt.id, d::date, rt.default_total_rooms, 0
FROM room_type rt
CROSS JOIN generate_series(current_date, current_date + 89, interval '1 day') AS d
ON CONFLICT (room_type_id, stay_date) DO NOTHING;

-- 날짜별 요금: 객실 타입별 기본가, 금·토요일 밤은 30% 할증 (날짜별 요금 구조 예시)
INSERT INTO room_rate (room_type_id, stay_date, price, currency)
SELECT rt.id,
       d::date,
       CASE WHEN EXTRACT(ISODOW FROM d) IN (5, 6) THEN base.price * 1.3 ELSE base.price END,
       'KRW'
FROM room_type rt
JOIN (VALUES
    ('스탠다드 더블', 120000),
    ('디럭스 트윈', 160000),
    ('오션뷰 디럭스', 200000),
    ('패밀리 스위트', 320000),
    ('가든 스튜디오', 150000),
    ('독채 풀빌라', 450000)
) AS base(room_name, price) ON base.room_name = rt.name
CROSS JOIN generate_series(current_date, current_date + 89, interval '1 day') AS d
ON CONFLICT (room_type_id, stay_date) DO NOTHING;
