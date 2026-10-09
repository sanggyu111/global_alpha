-- STAYPOINT 초기 스키마 (설계 docs/02-design.md 3.2)
-- 원칙: 정합성은 애플리케이션 코드만 믿지 않고 UNIQUE / CHECK / FK 제약으로도 보장한다.

-- ---------------------------------------------------------------
-- 숙소 · 객실
-- ---------------------------------------------------------------
CREATE TABLE property (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    address     VARCHAR(200) NOT NULL,
    region      VARCHAR(50)  NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_property_region ON property (region);

CREATE TABLE room_type (
    id                  BIGSERIAL PRIMARY KEY,
    property_id         BIGINT       NOT NULL REFERENCES property (id),
    name                VARCHAR(100) NOT NULL,
    capacity            INT          NOT NULL CHECK (capacity > 0),
    default_total_rooms INT          NOT NULL CHECK (default_total_rooms >= 0),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_room_type_property ON room_type (property_id);

-- ---------------------------------------------------------------
-- 날짜별 재고 — 동시성의 중심 (설계 4.1)
-- 예약 생성 시 조건부 UPDATE (booked_count < total_count) 로 차감한다.
-- CHECK 제약은 코드에 버그가 있어도 초과예약을 DB 가 거부하게 하는 최종 방어선.
-- ---------------------------------------------------------------
CREATE TABLE room_inventory (
    id           BIGSERIAL PRIMARY KEY,
    room_type_id BIGINT      NOT NULL REFERENCES room_type (id),
    stay_date    DATE        NOT NULL,
    total_count  INT         NOT NULL,
    booked_count INT         NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_room_inventory UNIQUE (room_type_id, stay_date),
    CONSTRAINT ck_room_inventory_total CHECK (total_count >= 0),
    CONSTRAINT ck_room_inventory_booked CHECK (booked_count >= 0 AND booked_count <= total_count)
);

-- 날짜별 요금 (주말·성수기 요금을 날짜 단위로 표현)
CREATE TABLE room_rate (
    id           BIGSERIAL PRIMARY KEY,
    room_type_id BIGINT        NOT NULL REFERENCES room_type (id),
    stay_date    DATE          NOT NULL,
    price        NUMERIC(12, 0) NOT NULL CHECK (price >= 0),
    currency     CHAR(3)       NOT NULL DEFAULT 'KRW',
    CONSTRAINT uq_room_rate UNIQUE (room_type_id, stay_date)
);

-- ---------------------------------------------------------------
-- 예약
-- ---------------------------------------------------------------
CREATE TABLE reservation (
    id              BIGSERIAL PRIMARY KEY,
    reservation_no  VARCHAR(30)    NOT NULL,
    user_id         VARCHAR(50)    NOT NULL,
    room_type_id    BIGINT         NOT NULL REFERENCES room_type (id),
    check_in        DATE           NOT NULL,
    check_out       DATE           NOT NULL,
    guest_count     INT            NOT NULL CHECK (guest_count > 0),
    guest_name      VARCHAR(50)    NOT NULL,
    guest_phone     VARCHAR(30)    NOT NULL,
    total_amount    NUMERIC(12, 0) NOT NULL CHECK (total_amount >= 0),
    status          VARCHAR(20)    NOT NULL,
    hold_expires_at TIMESTAMPTZ    NOT NULL,
    confirmed_at    TIMESTAMPTZ,
    canceled_at     TIMESTAMPTZ,
    cancel_reason   VARCHAR(30),
    refund_amount   NUMERIC(12, 0) CHECK (refund_amount >= 0),
    idempotency_key VARCHAR(100)   NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_reservation_no UNIQUE (reservation_no),
    -- 예약 버튼 연타 방지: 같은 사용자의 같은 키는 한 번만 (설계 4.4)
    CONSTRAINT uq_reservation_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT ck_reservation_dates CHECK (check_out > check_in),
    CONSTRAINT ck_reservation_status CHECK (status IN ('PENDING', 'CONFIRMED', 'COMPLETED', 'CANCELED'))
);

CREATE INDEX idx_reservation_status_hold ON reservation (status, hold_expires_at);
CREATE INDEX idx_reservation_user ON reservation (user_id);
CREATE INDEX idx_reservation_check_in ON reservation (check_in);
CREATE INDEX idx_reservation_room_type ON reservation (room_type_id, check_in);

-- 상태 전이 이력
CREATE TABLE reservation_history (
    id             BIGSERIAL PRIMARY KEY,
    reservation_id BIGINT      NOT NULL REFERENCES reservation (id),
    from_status    VARCHAR(20),
    to_status      VARCHAR(20) NOT NULL,
    reason         VARCHAR(100),
    actor          VARCHAR(50) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_reservation_history_reservation ON reservation_history (reservation_id);

-- ---------------------------------------------------------------
-- 결제
-- ---------------------------------------------------------------
CREATE TABLE payment (
    id              BIGSERIAL PRIMARY KEY,
    reservation_id  BIGINT         NOT NULL REFERENCES reservation (id),
    pg_order_id     VARCHAR(50)    NOT NULL,
    pg_tid          VARCHAR(50),
    amount          NUMERIC(12, 0) NOT NULL CHECK (amount >= 0),
    canceled_amount NUMERIC(12, 0) NOT NULL DEFAULT 0,
    status          VARCHAR(20)    NOT NULL,
    idempotency_key VARCHAR(100)   NOT NULL,
    fail_reason     VARCHAR(200),
    approved_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    -- 결제 버튼 연타 / 같은 요청 재전송 방지
    CONSTRAINT uq_payment_idempotency UNIQUE (idempotency_key),
    -- 중복 승인 통지는 pg_order_id 로 같은 결제를 찾아 한 번만 반영
    CONSTRAINT uq_payment_order UNIQUE (pg_order_id),
    CONSTRAINT uq_payment_tid UNIQUE (pg_tid),
    CONSTRAINT ck_payment_canceled CHECK (canceled_amount >= 0 AND canceled_amount <= amount),
    CONSTRAINT ck_payment_status CHECK (status IN ('READY', 'APPROVED', 'FAILED', 'CANCELED'))
);

-- 한 예약에 "진행 중이거나 승인된 결제" 는 최대 1건 → 서로 다른 키로 동시에 결제해도 DB 가 막는다.
-- 실패(FAILED) 후 재결제는 허용.
CREATE UNIQUE INDEX uq_payment_active_per_reservation
    ON payment (reservation_id) WHERE status IN ('READY', 'APPROVED');

CREATE INDEX idx_payment_status_created ON payment (status, created_at);

-- 결제 취소 요청 = 재시도 작업 큐 (아웃박스, 설계 4.6)
CREATE TABLE payment_cancel (
    id            BIGSERIAL PRIMARY KEY,
    payment_id    BIGINT         NOT NULL REFERENCES payment (id),
    cancel_amount NUMERIC(12, 0) NOT NULL CHECK (cancel_amount > 0),
    reason        VARCHAR(20)    NOT NULL,
    status        VARCHAR(20)    NOT NULL,
    -- PG 에 함께 보내는 멱등 키: 재시도해도 같은 취소가 두 번 실행되지 않음
    cancel_key    VARCHAR(100)   NOT NULL,
    attempt_count INT            NOT NULL DEFAULT 0,
    last_error    VARCHAR(500),
    next_retry_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_cancel_key UNIQUE (cancel_key),
    CONSTRAINT ck_payment_cancel_reason CHECK (reason IN ('USER_CANCEL', 'COMPENSATION')),
    CONSTRAINT ck_payment_cancel_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'MANUAL_REVIEW'))
);

CREATE INDEX idx_payment_cancel_status_retry ON payment_cancel (status, next_retry_at);

-- ---------------------------------------------------------------
-- 모의 PG 전용 테이블 (payment 패키지와 공유하지 않음, 설계 5장)
-- ---------------------------------------------------------------
CREATE TABLE mockpg_payment (
    id              BIGSERIAL PRIMARY KEY,
    order_id        VARCHAR(50)    NOT NULL,
    tid             VARCHAR(50),
    amount          NUMERIC(12, 0) NOT NULL,
    canceled_amount NUMERIC(12, 0) NOT NULL DEFAULT 0,
    status          VARCHAR(20)    NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    -- 같은 orderId 로 다시 승인 요청이 와도 처음 결과를 돌려준다 (이중 승인 없음)
    CONSTRAINT uq_mockpg_payment_order UNIQUE (order_id),
    CONSTRAINT uq_mockpg_payment_tid UNIQUE (tid)
);

CREATE TABLE mockpg_cancel (
    id         BIGSERIAL PRIMARY KEY,
    cancel_key VARCHAR(100)   NOT NULL,
    tid        VARCHAR(50)    NOT NULL,
    amount     NUMERIC(12, 0) NOT NULL,
    created_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_mockpg_cancel_key UNIQUE (cancel_key)
);
