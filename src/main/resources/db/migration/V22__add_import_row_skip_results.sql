DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    FOR constraint_name IN
        SELECT c.conname
        FROM pg_constraint c
        WHERE c.conrelid = 'import_row_result'::regclass
          AND c.contype = 'c'
          AND pg_get_constraintdef(c.oid) LIKE '%SUCCEEDED%'
    LOOP
        EXECUTE format('ALTER TABLE import_row_result DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END $$;

ALTER TABLE import_row_result
    ADD COLUMN skip_reason VARCHAR(30),
    ADD COLUMN matched_entity_id BIGINT,
    ADD CONSTRAINT ck_import_row_result_outcome
        CHECK (result IN ('SUCCEEDED', 'FAILED', 'SKIPPED')),
    ADD CONSTRAINT ck_import_row_result_skip
        CHECK ((result = 'SKIPPED') = (skip_reason IS NOT NULL)),
    ADD CONSTRAINT ck_import_row_result_skip_reason
        CHECK (skip_reason IS NULL OR skip_reason IN ('ALREADY_IMPORTED', 'DUPLICATE_IN_FILE', 'DUPLICATE_EXISTING')),
    ADD CONSTRAINT ck_import_row_result_matched
        CHECK (matched_entity_id IS NULL OR result = 'SKIPPED');

ALTER TABLE import_batch
    ADD COLUMN skipped_duplicate_count INT CHECK (skipped_duplicate_count >= 0);

ALTER TABLE import_batch DROP CONSTRAINT ck_import_batch_result_counts;
ALTER TABLE import_batch ADD CONSTRAINT ck_import_batch_result_counts
    CHECK ((succeeded_count IS NULL) = (failed_count IS NULL)
        AND (skipped_duplicate_count IS NULL OR succeeded_count IS NOT NULL)
        AND (row_count IS NULL OR succeeded_count IS NULL
            OR succeeded_count + failed_count + coalesce(skipped_duplicate_count, 0) <= row_count));

COMMENT ON COLUMN import_row_result.result IS '행 단위 실행 결과다. SUCCEEDED는 새 레코드를 만든 줄, FAILED는 실패한 줄, SKIPPED는 중복으로 만들지 않고 제외한 줄이다';
COMMENT ON COLUMN import_row_result.skip_reason IS '중복으로 제외한 이유다. ALREADY_IMPORTED는 같은 원본 ID가 이전에 이미 반입된 줄, DUPLICATE_IN_FILE은 같은 파일의 앞선 줄과 겹친 줄, DUPLICATE_EXISTING은 같은 사무소의 기존 고객과 이름·전화번호가 같은 줄이다. result가 SKIPPED일 때만 값이 있다';
COMMENT ON COLUMN import_row_result.matched_entity_id IS '중복으로 제외한 줄이 겹친 기존 레코드의 ID다. 겹친 대상을 특정할 수 없으면 NULL이다. result가 SKIPPED일 때만 값이 있을 수 있다';
COMMENT ON COLUMN import_batch.skipped_duplicate_count IS '실행 결과에서 중복으로 제외한 줄 수다. 성공 건수에 포함하지 않는다. 실행 전과 이 컬럼이 생기기 전에 끝난 실행은 NULL이다';
