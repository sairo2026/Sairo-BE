-- Adds the start time computed when a schedule composition is saved so the coordination detail can return it after a reload.
-- The column is nullable and has no default, so existing rows are not rewritten. V25 and earlier are not modified.

ALTER TABLE visit_candidate_property
    ADD COLUMN suggested_start_at TIMESTAMPTZ;

COMMENT ON COLUMN visit_candidate_property.suggested_start_at IS '일정 구성을 저장할 때 계산한 이 후보 매물의 방문 시작 시각이다. 확정이 아니며 신청 시간의 기본값으로만 쓴다. 일정 구성을 저장한 적이 없거나 마지막 저장의 순서 목록에서 빠졌으면 NULL이다';
