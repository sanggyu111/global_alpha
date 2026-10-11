-- T23 PG 오류 화면 (설계 8.5): 결제 승인 요청이 실패할 때마다 1행.
-- payment 에는 fail_reason 한 줄만 남고, 응답 없음(결과 모름) 이력은 어디에도 남지 않으므로 따로 기록한다.
-- PG 거절(PG_DECLINED)은 READY → FAILED 전이와 같은 트랜잭션에서만 기록 → 결제당 한 번.
-- 해결 여부는 저장하지 않는다 — payment.status 로 조회할 때 계산한다 (READY 가 아니면 결과 확정).
CREATE TABLE payment_approve_failure (
    id            BIGSERIAL PRIMARY KEY,
    payment_id    BIGINT       NOT NULL REFERENCES payment (id),
    error_code    VARCHAR(20)  NOT NULL,
    http_status   INT,
    error_message VARCHAR(500),
    occurred_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_payment_approve_failure_code
        CHECK (error_code IN ('PG_DECLINED', 'PG_5XX', 'PG_TIMEOUT', 'PG_CONNECTION', 'PG_REJECTED', 'PG_UNKNOWN'))
);
-- 관리자 화면의 기간 조회
CREATE INDEX idx_payment_approve_failure_occurred ON payment_approve_failure (occurred_at);
