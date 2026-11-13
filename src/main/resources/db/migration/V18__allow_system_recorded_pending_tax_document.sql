ALTER TABLE tax_document ALTER COLUMN recorded_by_operator_id DROP NOT NULL;

ALTER TABLE tax_document ADD CONSTRAINT ck_tax_document_recorder
    CHECK (recorded_by_operator_id IS NOT NULL OR status = 'PENDING');

COMMENT ON COLUMN tax_document.recorded_by_operator_id IS '기록한 결제담당 운영자다. 발급 대기 기록은 배치가 만들므로 비어 있고 운영자가 처리하면 채운다';
