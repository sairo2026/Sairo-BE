-- coordination.status·coordination_status_history.to_status CHECK에
-- SCHEDULE_CONFIRMED(확정 완료)·VISIT_COMPLETED(임장 완료) 추가.
-- V10의 COMPLETED·CANCELLED 및 관련 컬럼·제약(cancelled_at, cancel_reason,
-- ck_coordination_completed, ck_coordination_cancelled) 유지, 완전출시 전용
-- 조율 취소 흐름용. 1차MVP 코드는 이 두 값 미생성.
--
-- coordination_customer_response의 customer_name·customer_phone NOT NULL을
-- TENANT 행의 식별정보 필수와 BUYER 행의 식별정보 미수집.

ALTER TABLE coordination DROP CONSTRAINT coordination_status_check;
ALTER TABLE coordination ADD CONSTRAINT coordination_status_check
    CHECK (status IN ('TENANT_CHECKING','BUYER_DELIVERY_REQUIRED','BUYER_CHECKING','FINAL_CONFIRMATION_REQUIRED','COMPLETED','CANCELLED','SCHEDULE_CONFIRMED','VISIT_COMPLETED'));

ALTER TABLE coordination_status_history DROP CONSTRAINT coordination_status_history_to_status_check;
ALTER TABLE coordination_status_history ADD CONSTRAINT coordination_status_history_to_status_check
    CHECK (to_status IN ('TENANT_CHECKING','BUYER_DELIVERY_REQUIRED','BUYER_CHECKING','FINAL_CONFIRMATION_REQUIRED','COMPLETED','CANCELLED','SCHEDULE_CONFIRMED','VISIT_COMPLETED'));

ALTER TABLE coordination ADD CONSTRAINT ck_coordination_schedule_confirmed
    CHECK (status NOT IN ('SCHEDULE_CONFIRMED','VISIT_COMPLETED') OR (scheduled_at IS NOT NULL AND confirmed_at IS NOT NULL));

COMMENT ON COLUMN coordination.status IS '조율현황 8종 중 하나: 완전출시 6종(COMPLETED·CANCELLED 포함)에 1차MVP 전용 SCHEDULE_CONFIRMED·VISIT_COMPLETED를 더했다. 1차MVP 코드는 COMPLETED·CANCELLED를 생성하지 않는다';
COMMENT ON COLUMN coordination_status_history.to_status IS '변경 후 상태. coordination.status와 동일한 조율현황 8종 중 하나';
COMMENT ON CONSTRAINT ck_coordination_schedule_confirmed ON coordination IS 'SCHEDULE_CONFIRMED·VISIT_COMPLETED 상태는 확정 방문 일시·확정 시각과 항상 함께 존재. ck_coordination_completed의 COMPLETED 검증을 1차MVP 두 값으로 확장';

ALTER TABLE coordination_customer_response ALTER COLUMN customer_name DROP NOT NULL;
ALTER TABLE coordination_customer_response ALTER COLUMN customer_phone DROP NOT NULL;
ALTER TABLE coordination_customer_response ADD CONSTRAINT ck_customer_response_tenant_identity
    CHECK (
        (role = 'TENANT' AND customer_name IS NOT NULL AND customer_phone IS NOT NULL)
        OR (role = 'BUYER' AND customer_name IS NULL AND customer_phone IS NULL)
    );

COMMENT ON COLUMN coordination_customer_response.customer_name IS 'TENANT 필수, BUYER는 NULL';
COMMENT ON COLUMN coordination_customer_response.customer_phone IS 'TENANT 필수, BUYER는 NULL';
COMMENT ON CONSTRAINT ck_customer_response_tenant_identity ON coordination_customer_response IS 'TENANT 행은 이름·연락처 필수, BUYER 행은 둘 다 NULL';
