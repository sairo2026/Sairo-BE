ALTER TABLE support_inquiry_attachment
    ADD COLUMN object_deleted_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_support_inquiry_attachment_object_deleted
        CHECK (object_deleted_at IS NULL OR scan_status = 'REJECTED');

CREATE INDEX ix_support_inquiry_attachment_rejected_object
    ON support_inquiry_attachment (id)
    WHERE scan_status = 'REJECTED' AND object_deleted_at IS NULL;

COMMENT ON COLUMN support_inquiry_attachment.object_deleted_at IS '보안 검사에서 차단된 첨부의 저장소 객체를 삭제한 시각이다. 거부 상태에서 NULL이면 삭제를 마치지 못한 것이며 주기 작업이 다시 삭제한다';
