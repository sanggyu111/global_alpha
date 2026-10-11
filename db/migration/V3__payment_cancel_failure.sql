-- T22 자동 환불 실패 기록 (설계 8.4): PG 환불 시도가 실패할 때마다 1행.
-- payment_cancel 에는 마지막 오류 한 줄만 남아, 재시도로 성공한 건은 실패 흔적이 사라지므로 회차별로 따로 남긴다.
-- 해결 여부는 저장하지 않는다 — payment_cancel.status 하나만 사실로 두고 조회할 때 계산한다.
CREATE TABLE payment_cancel_failure (
    id                BIGSERIAL PRIMARY KEY,
    payment_cancel_id BIGINT       NOT NULL REFERENCES payment_cancel (id),
    attempt_no        INT          NOT NULL CHECK (attempt_no > 0),
    error_code        VARCHAR(20)  NOT NULL,
    http_status       INT,
    error_message     VARCHAR(500),
    occurred_at       TIMESTAMPTZ  NOT NULL,
    -- 같은 회차가 두 번 기록되지 않는다 (결과 반영은 payment_cancel 행 잠금 뒤에만 일어나지만 DB 로도 보장)
    CONSTRAINT uq_payment_cancel_failure_attempt UNIQUE (payment_cancel_id, attempt_no),
    CONSTRAINT ck_payment_cancel_failure_code
        CHECK (error_code IN ('PG_5XX', 'PG_TIMEOUT', 'PG_CONNECTION', 'PG_REJECTED', 'PG_UNKNOWN'))
);
-- 관리자 화면의 기간 조회
CREATE INDEX idx_payment_cancel_failure_occurred ON payment_cancel_failure (occurred_at);
