-- T20 환불 실패 데모: 모의 PG 가 결제(tid)별로 "앞으로 실패할 취소 횟수" 를 기억한다.
-- 재시도 스케줄러의 요청에도 같은 장애가 이어지게 하려고 장애 상태를 PG(외부 시스템) 쪽에 둔다 (설계 5장 T20).
ALTER TABLE mockpg_payment
    ADD COLUMN cancel_fail_remaining INT NOT NULL DEFAULT 0,
    ADD COLUMN cancel_fail_type      VARCHAR(20),
    ADD CONSTRAINT ck_mockpg_payment_cancel_fail_remaining CHECK (cancel_fail_remaining >= 0),
    ADD CONSTRAINT ck_mockpg_payment_cancel_fail_type
        CHECK (cancel_fail_type IS NULL OR cancel_fail_type IN ('UNAVAILABLE', 'REJECTED'));
