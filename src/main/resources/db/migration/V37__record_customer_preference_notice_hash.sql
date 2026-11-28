ALTER TABLE consent_guest_notice_ack
    ADD COLUMN supplemental_notice_hash VARCHAR(64);

ALTER TABLE consent_guest_notice_ack
    DROP CONSTRAINT ck_preference_notice_version;

ALTER TABLE consent_guest_notice_ack
    ADD CONSTRAINT ck_preference_notice_version
    CHECK (action_type <> 'PREFERENCE_SAVE' OR
        (supplemental_notice_version IS NOT NULL AND supplemental_notice_hash IS NOT NULL));
