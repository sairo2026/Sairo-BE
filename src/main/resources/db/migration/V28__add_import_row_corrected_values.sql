-- Adds the values a user corrected inside the app for a failed import row.
-- Only a nullable column is added to import_row_result. V27 and earlier are not modified. No rows are inserted, updated or deleted.

ALTER TABLE import_row_result ADD COLUMN corrected_values JSONB;

COMMENT ON COLUMN import_row_result.corrected_values IS '실패한 줄을 앱에서 고친 값이다. 키는 이전 대상 항목 이름이고 값은 고친 문자열이다. 실패한 줄만 다시 처리할 때 원본 파일의 값 대신 이 값을 쓴다. 고치지 않았으면 NULL이다';
