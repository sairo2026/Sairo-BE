-- login_history가 다른 회원의 로그인 수단을 가리키지 못하게 강제
-- coordination.origin_coordination_id가 다른 사무소 조율 또는 자기 자신을 가리키지 못하게 강제

ALTER TABLE login_identity
    ADD CONSTRAINT uq_login_identity_id_member UNIQUE (id, member_id);

ALTER TABLE login_history
    DROP CONSTRAINT login_history_login_identity_id_fkey;

ALTER TABLE login_history
    ADD CONSTRAINT fk_login_history_identity_member
    FOREIGN KEY (login_identity_id, member_id)
    REFERENCES login_identity (id, member_id)
    ON DELETE RESTRICT;

ALTER TABLE coordination
    DROP CONSTRAINT coordination_origin_coordination_id_fkey;

ALTER TABLE coordination
    ADD CONSTRAINT fk_coordination_origin_office
    FOREIGN KEY (origin_coordination_id, office_id)
    REFERENCES coordination (id, office_id)
    ON DELETE RESTRICT;

ALTER TABLE coordination ADD CONSTRAINT ck_coordination_origin_not_self CHECK (
    origin_coordination_id IS NULL OR origin_coordination_id <> id
);

COMMENT ON CONSTRAINT fk_login_history_identity_member ON login_history IS '로그인 기록이 자신이 소유한 로그인 수단만 참조하도록 강제(복합 FK)';
COMMENT ON CONSTRAINT fk_coordination_origin_office ON coordination IS '재조율 원본이 같은 사무소의 조율만 가리키도록 강제(복합 FK)';
