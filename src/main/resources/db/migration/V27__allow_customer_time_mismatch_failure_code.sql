-- Splits the customer-side revalidation failure into CUSTOMER_TIME_MISMATCH (outside the customer's available time) and CUSTOMER_CONFLICT (overlap with another confirmed visit of the customer).
-- Only the allowed value list of visit_request_revalidation.failure_code is widened. V26 and earlier are not modified. No rows are inserted, updated or deleted.

ALTER TABLE visit_request_revalidation DROP CONSTRAINT visit_request_revalidation_failure_code_check;
ALTER TABLE visit_request_revalidation ADD CONSTRAINT visit_request_revalidation_failure_code_check
    CHECK (failure_code IS NULL OR failure_code IN ('CONDITION_EXPIRED','CONDITION_CHANGED','NO_PERMISSION','WINDOW_MISMATCH','MAX_VISITS_PER_DAY','MIN_GAP','MIN_LEAD','SINGLE_VISIT_MAX','CUSTOMER_TIME_MISMATCH','CUSTOMER_CONFLICT','BROKER_CONFLICT','PROPERTY_CONFLICT','TRAVEL_CONFLICT','DEPUTY_VISIT_LOCKED'));

COMMENT ON COLUMN visit_request_revalidation.failure_code IS '재검증에 실패한 첫 규칙의 코드다. CUSTOMER_TIME_MISMATCH는 신청 구간이 고객이 낸 가능 시간 밖인 경우, CUSTOMER_CONFLICT는 고객의 다른 확정 방문이나 이동 구간과 겹치는 경우다. 성공이면 NULL이다';
