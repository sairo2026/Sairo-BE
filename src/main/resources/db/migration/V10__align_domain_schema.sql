-- 운영 DB 도메인 테이블 전체 0행 확인. 기존 데이터 보존 없이 재구성.

-- ---------------------------------------------------------------------
-- 회원가입·사업자인증 진행 상태 테이블 제거
-- ---------------------------------------------------------------------

DROP TABLE document_move_outbox;
DROP TABLE oauth_transaction;
DROP TABLE kakao_signup_ticket;

-- ---------------------------------------------------------------------
-- 인증: member -> app_user, login_identity 흡수, login_history 정리
-- ---------------------------------------------------------------------

ALTER TABLE login_history DROP CONSTRAINT fk_login_history_identity_member;
ALTER TABLE login_history DROP COLUMN login_identity_id;
ALTER TABLE login_history DROP COLUMN is_new_device;

DROP TABLE login_identity;

ALTER TABLE member RENAME TO app_user;
ALTER TABLE app_user RENAME COLUMN display_name TO name;
ALTER TABLE app_user ALTER COLUMN name TYPE VARCHAR(50);

DROP INDEX uq_member_email_active;
ALTER TABLE app_user DROP COLUMN email;
ALTER TABLE app_user DROP COLUMN profile_image_url;

ALTER TABLE app_user ADD COLUMN kakao_provider_key VARCHAR(255);
ALTER TABLE app_user ALTER COLUMN kakao_provider_key SET NOT NULL;
ALTER TABLE app_user ADD CONSTRAINT app_user_kakao_provider_key_key UNIQUE (kakao_provider_key);

ALTER TABLE app_user DROP CONSTRAINT member_account_status_check;
ALTER TABLE app_user ADD CONSTRAINT app_user_account_status_check
    CHECK (account_status IN ('ACTIVE','WITHDRAWN'));

ALTER TABLE app_user RENAME CONSTRAINT ck_member_withdrawn TO ck_app_user_withdrawn;
ALTER TABLE app_user RENAME CONSTRAINT member_pkey TO app_user_pkey;

COMMENT ON TABLE app_user IS '카카오로 인증한 사용자';
COMMENT ON COLUMN app_user.kakao_provider_key IS '카카오 고유 회원번호';
COMMENT ON COLUMN app_user.name IS '담당자 표시 이름';
COMMENT ON COLUMN app_user.account_status IS '계정 상태: ACTIVE 정상, WITHDRAWN 탈퇴';
COMMENT ON COLUMN app_user.withdrawn_at IS '탈퇴 처리 일시. WITHDRAWN이 아니면 NULL';

ALTER TABLE login_history RENAME COLUMN member_id TO user_id;
ALTER TABLE login_history DROP CONSTRAINT login_history_member_id_fkey;
ALTER TABLE login_history ADD FOREIGN KEY (user_id) REFERENCES app_user (id);
ALTER INDEX ix_login_history_member_created RENAME TO ix_login_history_user_created;

COMMENT ON TABLE login_history IS '로그인 시도 기록. 최근 로그인 조회에 사용하며 세션 자체는 별도 세션 저장소가 관리한다';
COMMENT ON COLUMN login_history.user_id IS '로그인한 사용자';

-- ---------------------------------------------------------------------
-- 약관
-- ---------------------------------------------------------------------

ALTER TABLE terms_catalog DROP CONSTRAINT ck_terms_catalog_version_format;
ALTER TABLE terms_catalog DROP COLUMN withdrawable;
ALTER TABLE terms_catalog DROP COLUMN created_at;
ALTER TABLE terms_catalog ALTER COLUMN content_hash TYPE CHAR(64);

ALTER TABLE terms_agreement RENAME COLUMN member_id TO user_id;
ALTER TABLE terms_agreement DROP CONSTRAINT terms_agreement_member_id_fkey;
ALTER TABLE terms_agreement ADD FOREIGN KEY (user_id) REFERENCES app_user (id);
ALTER TABLE terms_agreement DROP CONSTRAINT fk_terms_agreement_catalog;
ALTER TABLE terms_agreement ADD FOREIGN KEY (terms_code, terms_version) REFERENCES terms_catalog (terms_code, terms_version);
DROP INDEX ix_terms_agreement_member_code;

COMMENT ON COLUMN terms_agreement.user_id IS '이벤트의 대상 사용자';

-- ---------------------------------------------------------------------
-- 사무소: office, office_registration, office_membership과 각 이력 테이블
-- ---------------------------------------------------------------------

ALTER TABLE office ADD COLUMN address VARCHAR(300);
UPDATE office SET address = address_base;
ALTER TABLE office ALTER COLUMN address SET NOT NULL;
ALTER TABLE office DROP COLUMN address_base;
ALTER TABLE office DROP COLUMN address_detail;
ALTER TABLE office ALTER COLUMN phone TYPE VARCHAR(30);

COMMENT ON COLUMN office.address IS '사무소 주소';

ALTER TABLE office_registration RENAME COLUMN applicant_member_id TO applicant_user_id;
ALTER TABLE office_registration DROP CONSTRAINT office_registration_applicant_member_id_fkey;
ALTER TABLE office_registration ADD FOREIGN KEY (applicant_user_id) REFERENCES app_user (id);

ALTER TABLE office_registration ALTER COLUMN requested_phone TYPE VARCHAR(30);
ALTER TABLE office_registration ALTER COLUMN status TYPE VARCHAR(30);
ALTER TABLE office_registration ALTER COLUMN status DROP DEFAULT;

ALTER TABLE office_registration DROP CONSTRAINT ck_office_registration_storage;
ALTER TABLE office_registration DROP CONSTRAINT office_registration_document_storage_status_check;
ALTER TABLE office_registration ADD COLUMN evidence_object_key VARCHAR(500);
ALTER TABLE office_registration DROP COLUMN document_object_key;
ALTER TABLE office_registration DROP COLUMN document_original_filename;
ALTER TABLE office_registration DROP COLUMN document_mime_type;
ALTER TABLE office_registration DROP COLUMN document_size_bytes;
ALTER TABLE office_registration DROP COLUMN document_etag;
ALTER TABLE office_registration DROP COLUMN document_checksum_sha256;
ALTER TABLE office_registration DROP COLUMN document_storage_status;

ALTER TABLE office_registration ADD COLUMN requested_address VARCHAR(300);
UPDATE office_registration SET requested_address = requested_address_base;
ALTER TABLE office_registration ALTER COLUMN requested_address SET NOT NULL;
ALTER TABLE office_registration DROP COLUMN requested_address_base;
ALTER TABLE office_registration DROP COLUMN requested_address_detail;

ALTER TABLE office_registration RENAME COLUMN reapplication_started_at TO resolved_at;

DROP INDEX uq_office_registration_result;
ALTER TABLE office_registration DROP CONSTRAINT office_registration_resulting_office_id_fkey;
ALTER TABLE office_registration ADD FOREIGN KEY (resulting_office_id) REFERENCES office (id);
ALTER TABLE office_registration ADD CONSTRAINT office_registration_resulting_office_id_key UNIQUE (resulting_office_id);

ALTER TABLE office_registration DROP CONSTRAINT office_registration_status_check;
ALTER TABLE office_registration ADD CONSTRAINT office_registration_status_check
    CHECK (status IN ('PENDING_REVIEW','EVIDENCE_REQUESTED','APPROVED','REJECTED','CANCELLED'));

DROP INDEX ix_office_registration_bizno_status;

DROP INDEX uq_office_registration_pending_applicant;
DROP INDEX uq_office_registration_pending_bizno;
CREATE UNIQUE INDEX uq_office_registration_pending_bizno
    ON office_registration (business_registration_number)
    WHERE status IN ('PENDING_REVIEW','EVIDENCE_REQUESTED');
CREATE UNIQUE INDEX uq_office_registration_pending_applicant
    ON office_registration (applicant_user_id)
    WHERE status IN ('PENDING_REVIEW','EVIDENCE_REQUESTED');

ALTER TABLE office_registration DROP CONSTRAINT ck_office_registration_status;
ALTER TABLE office_registration ADD CONSTRAINT ck_office_registration_status CHECK (
    (status IN ('PENDING_REVIEW','EVIDENCE_REQUESTED')
        AND resulting_office_id IS NULL AND rejection_reason IS NULL AND reviewed_at IS NULL AND cancelled_at IS NULL AND resolved_at IS NULL)
    OR (status = 'APPROVED'
        AND resulting_office_id IS NOT NULL AND rejection_reason IS NULL AND reviewed_at IS NOT NULL AND cancelled_at IS NULL AND resolved_at IS NULL)
    OR (status = 'REJECTED'
        AND resulting_office_id IS NULL AND rejection_reason IS NOT NULL AND reviewed_at IS NOT NULL AND cancelled_at IS NULL)
    OR (status = 'CANCELLED'
        AND resulting_office_id IS NULL AND rejection_reason IS NULL AND reviewed_at IS NULL AND cancelled_at IS NOT NULL AND resolved_at IS NULL)
);

COMMENT ON TABLE office_registration IS '신규 사무소 등록 신청. 신청자가 제출·수정한 값을 심사 대상 스냅샷으로 보관하며, 승인 시 office는 이 행의 값이 아니라 사이로팀이 최종 확정한 값으로 생성한다. 자동 승인 경로는 없다';
COMMENT ON COLUMN office_registration.applicant_user_id IS '신청자';
COMMENT ON COLUMN office_registration.requested_address IS '신청 주소(자동완성 후 수정 가능)';
COMMENT ON COLUMN office_registration.evidence_object_key IS '증빙서류 객체 스토리지 키(사이로팀 자체 확인만으로 승인되면 NULL)';
COMMENT ON COLUMN office_registration.resolved_at IS '재신청 시작으로 이 반려 건이 해결됨 처리된 일시. REJECTED에서만 값이 있을 수 있고, 값이 있으면 AuthContext 판정에서 미해결로 취급하지 않는다';

ALTER TABLE office_registration_status_history RENAME COLUMN office_registration_id TO registration_id;
ALTER TABLE office_registration_status_history
    DROP CONSTRAINT office_registration_status_history_office_registration_id_fkey;
ALTER TABLE office_registration_status_history
    ADD FOREIGN KEY (registration_id) REFERENCES office_registration (id);

ALTER TABLE office_registration_status_history ALTER COLUMN from_status TYPE VARCHAR(30);
ALTER TABLE office_registration_status_history ALTER COLUMN to_status TYPE VARCHAR(30);

ALTER TABLE office_registration_status_history
    DROP CONSTRAINT office_registration_status_history_from_status_check;
ALTER TABLE office_registration_status_history ADD CONSTRAINT office_registration_status_history_from_status_check
    CHECK (from_status IN ('PENDING_REVIEW','EVIDENCE_REQUESTED','APPROVED','REJECTED','CANCELLED'));

ALTER TABLE office_registration_status_history
    DROP CONSTRAINT office_registration_status_history_to_status_check;
ALTER TABLE office_registration_status_history ADD CONSTRAINT office_registration_status_history_to_status_check
    CHECK (to_status IN ('PENDING_REVIEW','EVIDENCE_REQUESTED','APPROVED','REJECTED','CANCELLED'));

ALTER TABLE office_registration_status_history DROP CONSTRAINT ck_reg_history_actor;
ALTER TABLE office_registration_status_history ADD CONSTRAINT ck_registration_history_actor
    CHECK ((changed_by_type = 'OPERATOR') = (changed_by_operator_id IS NOT NULL));

COMMENT ON COLUMN office_registration_status_history.registration_id IS '대상 신청';

-- ---------------------------------------------------------------------
-- 매물·계약·D-90 후속 업무
-- ---------------------------------------------------------------------

ALTER TABLE property DROP CONSTRAINT fk_property_deleted_by_membership;
ALTER TABLE property DROP CONSTRAINT ck_property_soft_delete;
ALTER TABLE property DROP COLUMN updated_at;
ALTER TABLE property DROP COLUMN deleted_by_member_id;

ALTER TABLE property RENAME COLUMN name TO property_name;
ALTER TABLE property ALTER COLUMN property_name TYPE VARCHAR(100);
ALTER TABLE property RENAME COLUMN address_base TO address;
ALTER TABLE property ALTER COLUMN address TYPE VARCHAR(300);
ALTER TABLE property ALTER COLUMN property_status TYPE VARCHAR(20);

ALTER TABLE property DROP CONSTRAINT property_office_id_fkey;
ALTER TABLE property ADD FOREIGN KEY (office_id) REFERENCES office (id);

ALTER TABLE property DROP CONSTRAINT property_property_status_check;
ALTER TABLE property ADD CONSTRAINT property_property_status_check
    CHECK (property_status IN ('ACTIVE','NO_CONTRACT','NEGOTIATING'));

DROP INDEX ix_property_active;
DROP INDEX ix_property_archived;

COMMENT ON TABLE property IS '사무소가 관리하는 매물';
COMMENT ON COLUMN property.address IS '매물 기본주소(필수)';
COMMENT ON COLUMN property.property_name IS '매물 별칭(선택)';
COMMENT ON COLUMN property.deleted_at IS '소프트 삭제 일시. 진행 중 임장 조율·진행 중 계약이 있으면 삭제할 수 없다';

ALTER TABLE property_contract DROP CONSTRAINT fk_property_contract_created_by_membership;
ALTER TABLE property_contract DROP CONSTRAINT fk_property_contract_property_office_type;
ALTER TABLE property_contract DROP CONSTRAINT fk_property_contract_previous_same_property;
DROP INDEX uq_property_contract_previous;

ALTER TABLE property DROP CONSTRAINT property_id_office_id_deal_type_key;

ALTER TABLE expiry_task DROP CONSTRAINT fk_expiry_task_contract_office_property;

ALTER TABLE property_contract DROP CONSTRAINT property_contract_id_office_id_property_id_deal_type_key;

ALTER TABLE property_contract DROP COLUMN created_by_member_id;
ALTER TABLE property_contract DROP COLUMN updated_at;

ALTER TABLE property_contract ALTER COLUMN contract_start_date DROP NOT NULL;
ALTER TABLE property_contract ALTER COLUMN owner_party_name DROP NOT NULL;
ALTER TABLE property_contract ALTER COLUMN owner_party_phone DROP NOT NULL;
ALTER TABLE property_contract ALTER COLUMN owner_party_phone TYPE VARCHAR(30);
ALTER TABLE property_contract ALTER COLUMN counterparty_name DROP NOT NULL;
ALTER TABLE property_contract ALTER COLUMN counterparty_phone DROP NOT NULL;
ALTER TABLE property_contract ALTER COLUMN counterparty_phone TYPE VARCHAR(30);
ALTER TABLE property_contract ALTER COLUMN end_reason TYPE TEXT;

ALTER TABLE property_contract ADD FOREIGN KEY (property_id, office_id) REFERENCES property (id, office_id);
ALTER TABLE property_contract ADD FOREIGN KEY (previous_contract_id) REFERENCES property_contract (id);
ALTER TABLE property_contract ADD UNIQUE (id, office_id);

ALTER TABLE property_contract ALTER COLUMN status DROP DEFAULT;

ALTER TABLE property_contract DROP CONSTRAINT ck_property_contract_amounts;
ALTER TABLE property_contract DROP CONSTRAINT ck_property_contract_period;
ALTER TABLE property_contract DROP CONSTRAINT ck_property_contract_previous_not_self;
ALTER TABLE property_contract DROP CONSTRAINT ck_property_contract_status_fields;

ALTER TABLE property_contract DROP CONSTRAINT property_contract_status_check;
ALTER TABLE property_contract ADD CONSTRAINT property_contract_status_check
    CHECK (status IN ('ACTIVE','RENEWED','COMPLETED','TERMINATED','CANCELLED'));

ALTER TABLE property_contract DROP CONSTRAINT ck_property_contract_status_by_deal_type;
ALTER TABLE property_contract ADD CONSTRAINT ck_property_contract_sale_terminal
    CHECK (deal_type <> 'SALE' OR status IN ('ACTIVE','COMPLETED','CANCELLED'));

DROP INDEX ix_property_contract_office_status_start;
DROP INDEX ix_property_contract_property_created;
ALTER INDEX uq_property_contract_active RENAME TO uq_property_active_contract;

COMMENT ON TABLE property_contract IS '매물의 현재·과거 계약 이력, 매물과 별도 저장. 재계약 시 새 행 추가, 기존 행 수정 없음';
COMMENT ON COLUMN property_contract.property_id IS '계약 대상 매물. office_id와 복합 FK로 같은 사무소 매물만 참조';
COMMENT ON COLUMN property_contract.previous_contract_id IS '재계약(RENEWED)으로 이 계약을 만든 경우 직전 계약';
COMMENT ON COLUMN property_contract.end_reason IS '중도 종료·계약 취소 사유';
COMMENT ON CONSTRAINT ck_property_contract_sale_terminal ON property_contract IS '매매는 재계약(RENEWED)·중도종료(TERMINATED) 전이 불가. 기간 없는 1회성 거래, 거래완료·취소로만 종료';

ALTER TABLE expiry_task DROP CONSTRAINT ck_expiry_task_target_date;
ALTER TABLE expiry_task DROP CONSTRAINT ck_expiry_task_type;
ALTER TABLE expiry_task DROP CONSTRAINT expiry_task_deal_type_check;
ALTER TABLE expiry_task DROP CONSTRAINT expiry_task_cancel_reason_check;
ALTER TABLE expiry_task DROP CONSTRAINT expiry_task_property_contract_id_task_type_key;

ALTER TABLE expiry_task DROP COLUMN deal_type;
ALTER TABLE expiry_task DROP COLUMN task_type;
ALTER TABLE expiry_task DROP COLUMN contract_end_date;
ALTER TABLE expiry_task DROP COLUMN completed_at;

ALTER TABLE expiry_task RENAME COLUMN property_contract_id TO contract_id;
ALTER TABLE expiry_task ALTER COLUMN cancel_reason TYPE VARCHAR(40);
ALTER TABLE expiry_task ALTER COLUMN status DROP DEFAULT;

ALTER TABLE expiry_task ADD CONSTRAINT expiry_task_contract_id_key UNIQUE (contract_id);
ALTER TABLE expiry_task ADD FOREIGN KEY (property_id, office_id) REFERENCES property (id, office_id);
ALTER TABLE expiry_task ADD FOREIGN KEY (contract_id, office_id, property_id) REFERENCES property_contract (id, office_id, property_id);

DROP INDEX ix_expiry_task_office_status_target;

COMMENT ON TABLE expiry_task IS '전세·월세 계약 종료 90일 전 자동 생성되는 후속 업무. 매매에는 생성하지 않는다. 계약 1건당 최대 1개만 생성한다';
COMMENT ON COLUMN expiry_task.contract_id IS '기준이 되는 전세·월세 계약. 계약 1건당 업무 1개만 허용';
COMMENT ON COLUMN expiry_task.status IS '업무 상태: OPEN 진행 중, COMPLETED 완료(스키마 확장용, 현재 사용자 흐름은 도달하지 않음), CANCELLED 취소';
COMMENT ON COLUMN expiry_task.cancel_reason IS '취소 사유 코드(재계약, 정상종료, 중도종료, 계약취소, 매물삭제 등)';

-- ---------------------------------------------------------------------
-- 임장 조율: 세입자 1명 + 구매자 여러 명 모델로 재작성
-- ---------------------------------------------------------------------
-- 고객 1명이 조율에 직결된 링크 하나로 응답하던 모델을, 세입자·구매자 응답을
-- coordination_customer_response로 분리해 응답별로 후보·링크를 독립 소유하는
-- 모델로 데이터 모델 자체를 바꾼다.

DROP TABLE coordination_status_history, coordination, coordination_candidate_time;

CREATE TABLE coordination (
    id                            BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    office_id                     BIGINT       NOT NULL,
    property_id                   BIGINT       NOT NULL,
    status                        VARCHAR(40)  NOT NULL
                                   CHECK (status IN ('TENANT_CHECKING','BUYER_DELIVERY_REQUIRED','BUYER_CHECKING','FINAL_CONFIRMATION_REQUIRED','COMPLETED','CANCELLED')),
    selected_buyer_response_id    BIGINT,
    confirmed_candidate_time_id   BIGINT,
    scheduled_at                  TIMESTAMPTZ,
    confirmed_at                  TIMESTAMPTZ,
    cancelled_at                  TIMESTAMPTZ,
    cancel_reason                 TEXT,
    created_by_membership_id      BIGINT       NOT NULL,
    created_at                    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (id, office_id),
    FOREIGN KEY (property_id, office_id) REFERENCES property (id, office_id),
    FOREIGN KEY (created_by_membership_id, office_id) REFERENCES office_membership (id, office_id)
);

ALTER TABLE coordination ADD CONSTRAINT ck_coordination_completed
    CHECK (status <> 'COMPLETED' OR (scheduled_at IS NOT NULL AND confirmed_at IS NOT NULL));
ALTER TABLE coordination ADD CONSTRAINT ck_coordination_cancelled
    CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL));

COMMENT ON TABLE coordination IS '한 매물에서 한 번의 방문 일시를 확정하기 위한 조율 라운드. 조율현황은 이 테이블에 하나만 존재한다';
COMMENT ON COLUMN coordination.office_id IS '조율 건을 소유한 사무소';
COMMENT ON COLUMN coordination.property_id IS '조율 대상 매물. office_id와 복합 FK';
COMMENT ON COLUMN coordination.status IS '조율현황 6종 중 하나. CANCELLED는 완료 전 어느 단계에서든, 완료 후에도 방문 예정일 전이면 도달 가능하다';
COMMENT ON COLUMN coordination.selected_buyer_response_id IS '최종 확정에서 선택된 구매자 응답. fk_selected_buyer 복합 FK로 같은 조율 건만 참조';
COMMENT ON COLUMN coordination.confirmed_candidate_time_id IS '최종 확정된 방문 후보 시간. fk_confirmed_candidate 복합 FK로 같은 조율 건만 참조';
COMMENT ON COLUMN coordination.scheduled_at IS '확정된 방문 일시. COMPLETED에서만 값 존재. 완료 후 CANCELLED로 전이해도 이 값은 유지된다';
COMMENT ON COLUMN coordination.confirmed_at IS '최종 확정 처리 시각. COMPLETED에서만 값 존재';
COMMENT ON COLUMN coordination.cancelled_at IS '조율 취소 처리 시각. CANCELLED 상태와 정확히 함께 존재한다';
COMMENT ON COLUMN coordination.cancel_reason IS '취소 사유, 선택 입력. 사유 불문 취소 가능';
COMMENT ON COLUMN coordination.created_by_membership_id IS '조율 건을 생성한 사용자 소속. office_id와 함께 복합 FK';
COMMENT ON CONSTRAINT ck_coordination_completed ON coordination IS 'COMPLETED 상태는 반드시 확정 방문 일시·확정 시각과 함께 존재';
COMMENT ON CONSTRAINT ck_coordination_cancelled ON coordination IS 'CANCELLED 상태와 취소 시각은 항상 함께 존재하거나 함께 없음. 완료 후 취소 전이 시 scheduled_at·confirmed_at 유지, 제약 위반 아님';

CREATE TABLE coordination_candidate_time (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    coordination_id  BIGINT      NOT NULL REFERENCES coordination(id) ON DELETE CASCADE,
    starts_at        TIMESTAMPTZ NOT NULL,
    UNIQUE (coordination_id, starts_at),
    UNIQUE (id, coordination_id)
);

COMMENT ON TABLE coordination_candidate_time IS '직원이 세입자용 링크 생성 시 제안하는 원본 후보 시간';
COMMENT ON COLUMN coordination_candidate_time.coordination_id IS '이 후보가 속한 조율 건';
COMMENT ON COLUMN coordination_candidate_time.starts_at IS '방문 후보 일시. 같은 조율 안에서 시각 중복 불가';

CREATE TABLE coordination_customer_response (
    id               BIGINT      GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    coordination_id  BIGINT      NOT NULL REFERENCES coordination(id) ON DELETE CASCADE,
    role             VARCHAR(10) NOT NULL CHECK (role IN ('TENANT','BUYER')),
    customer_name    VARCHAR(50) NOT NULL,
    customer_phone   VARCHAR(30) NOT NULL,
    result           VARCHAR(30) NOT NULL
                      CHECK (result IN ('WAITING','AVAILABLE_SUBMITTED','NONE_AVAILABLE','EXPIRED','CONFIRMED','NOT_SELECTED')),
    submitted_at     TIMESTAMPTZ,
    reset_count      INT         NOT NULL DEFAULT 0 CHECK (reset_count >= 0),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, coordination_id)
);

CREATE UNIQUE INDEX uq_coordination_tenant
    ON coordination_customer_response (coordination_id)
    WHERE role = 'TENANT';

COMMENT ON TABLE coordination_customer_response IS '조율 건 안의 세입자 응답 1개와 구매자별 응답. 세입자 1명·구매자 여러 명을 이 테이블 하나로 표현한다';
COMMENT ON COLUMN coordination_customer_response.coordination_id IS '이 응답이 속한 조율 건';
COMMENT ON COLUMN coordination_customer_response.role IS '고객 역할: TENANT(조율당 정확히 1개), BUYER(0개 이상)';
COMMENT ON COLUMN coordination_customer_response.customer_name IS '고객 이름(매번 직접 입력)';
COMMENT ON COLUMN coordination_customer_response.customer_phone IS '고객 연락처';
COMMENT ON COLUMN coordination_customer_response.result IS '처리결과 6종. 최종 확정 시 세입자·선택 구매자는 CONFIRMED, 나머지 구매자는 NOT_SELECTED';
COMMENT ON COLUMN coordination_customer_response.submitted_at IS '고객이 응답을 제출한 시각';
COMMENT ON COLUMN coordination_customer_response.reset_count IS '고객 응답 취소·재시작으로 초기화된 횟수';

ALTER TABLE coordination ADD CONSTRAINT fk_selected_buyer
    FOREIGN KEY (selected_buyer_response_id, id) REFERENCES coordination_customer_response (id, coordination_id);
ALTER TABLE coordination ADD CONSTRAINT fk_confirmed_candidate
    FOREIGN KEY (confirmed_candidate_time_id, id) REFERENCES coordination_candidate_time (id, coordination_id);

COMMENT ON CONSTRAINT fk_selected_buyer ON coordination IS '선택 구매자 응답이 같은 조율 건 소속인지 DB가 직접 검증';
COMMENT ON CONSTRAINT fk_confirmed_candidate ON coordination IS '확정 후보 시간이 같은 조율 건 소속인지 DB가 직접 검증';

CREATE TABLE customer_response_candidate (
    response_id        BIGINT  NOT NULL,
    coordination_id    BIGINT  NOT NULL,
    candidate_time_id  BIGINT  NOT NULL,
    is_selected         BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (response_id, candidate_time_id),
    FOREIGN KEY (response_id, coordination_id) REFERENCES coordination_customer_response (id, coordination_id) ON DELETE CASCADE,
    FOREIGN KEY (candidate_time_id, coordination_id) REFERENCES coordination_candidate_time (id, coordination_id) ON DELETE CASCADE
);

COMMENT ON TABLE customer_response_candidate IS '고객 응답별로 제공된 후보와 선택 여부. 두 복합 FK가 같은 coordination_id를 요구해 다른 조율 건 데이터가 섞이지 않는다';
COMMENT ON COLUMN customer_response_candidate.response_id IS '고객 응답';
COMMENT ON COLUMN customer_response_candidate.coordination_id IS '응답·후보가 공통으로 속한 조율 건';
COMMENT ON COLUMN customer_response_candidate.candidate_time_id IS '이 응답에 제공된 후보 시간';
COMMENT ON COLUMN customer_response_candidate.is_selected IS '고객이 실제로 선택했는지(복수 선택 가능)';

CREATE TABLE customer_response_link (
    id            BIGINT      GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    response_id   BIGINT      NOT NULL REFERENCES coordination_customer_response(id) ON DELETE CASCADE,
    token_hash    CHAR(64)    NOT NULL UNIQUE,
    issued_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    revoked_at    TIMESTAMPTZ,
    CHECK (expires_at = issued_at + interval '7 days'),
    CHECK (revoked_at IS NULL OR revoked_at >= issued_at)
);

CREATE UNIQUE INDEX uq_response_active_link
    ON customer_response_link (response_id)
    WHERE revoked_at IS NULL;

COMMENT ON TABLE customer_response_link IS '고객 응답별 공개 링크 발급 이력. 토큰 원문은 저장하지 않고 SHA-256 해시만 저장한다. 링크 재발급은 새 행을 추가하고 이전 행을 폐기하는 것으로 처리한다';
COMMENT ON COLUMN customer_response_link.response_id IS '이 링크가 속한 고객 응답';
COMMENT ON COLUMN customer_response_link.token_hash IS '토큰 원문의 SHA-256 해시';
COMMENT ON COLUMN customer_response_link.expires_at IS '만료 시각. 발급 시점부터 정확히 7일 뒤';
COMMENT ON COLUMN customer_response_link.revoked_at IS '폐기 시각. NULL이면 활성(응답당 최대 1개)';

CREATE TABLE coordination_status_history (
    id                     BIGINT      GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    coordination_id        BIGINT      NOT NULL REFERENCES coordination(id) ON DELETE CASCADE,
    from_status            VARCHAR(40),
    to_status              VARCHAR(40) NOT NULL
                            CHECK (to_status IN ('TENANT_CHECKING','BUYER_DELIVERY_REQUIRED','BUYER_CHECKING','FINAL_CONFIRMATION_REQUIRED','COMPLETED','CANCELLED')),
    actor_membership_id    BIGINT      REFERENCES office_membership(id),
    source                 VARCHAR(20) NOT NULL CHECK (source IN ('STAFF','TENANT','BUYER','SYSTEM')),
    reason                 TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE coordination_status_history IS '조율현황 전이 감사 이력. 확정률은 현재 상태가 아니라 이 이력의 COMPLETED 도달 여부로 계산(COMPLETED→CANCELLED 재전이 가능). to_status 값은 CHECK로 제한, from→to 전이 조합은 서비스 계층이 같은 트랜잭션에서 강제(전이표: API 명세서 5장)';
COMMENT ON COLUMN coordination_status_history.coordination_id IS '대상 조율 건';
COMMENT ON COLUMN coordination_status_history.from_status IS '변경 전 상태(최초 생성 시 NULL)';
COMMENT ON COLUMN coordination_status_history.to_status IS '변경 후 상태';
COMMENT ON COLUMN coordination_status_history.actor_membership_id IS '전이를 발생시킨 사용자 소속. source가 고객·시스템이면 NULL 가능';
COMMENT ON COLUMN coordination_status_history.source IS '전이 주체: STAFF/TENANT/BUYER/SYSTEM';
COMMENT ON COLUMN coordination_status_history.reason IS '전이 사유(취소 사유 등)';

CREATE INDEX ix_coordination_office_status ON coordination (office_id, status, created_at DESC);
CREATE INDEX ix_response_coordination ON coordination_customer_response (coordination_id, role);
CREATE INDEX ix_link_hash ON customer_response_link (token_hash);

COMMENT ON INDEX ix_coordination_office_status IS '임장 조율 목록의 사무소별·조율현황별 최신순 조회';
COMMENT ON INDEX ix_response_coordination IS '조율 상세에서 세입자 응답 1개·구매자별 응답을 role 순으로 조회';
COMMENT ON INDEX ix_link_hash IS '공개 링크 진입에서 토큰 해시로 현재 활성 링크를 조회';

-- ---------------------------------------------------------------------
-- 운영자·시스템 운영
-- ---------------------------------------------------------------------

ALTER TABLE operator RENAME TO operator_account;
ALTER TABLE operator_account RENAME CONSTRAINT operator_pkey TO operator_account_pkey;
ALTER TABLE operator_account RENAME CONSTRAINT operator_status_check TO operator_account_status_check;
ALTER TABLE operator_account DROP CONSTRAINT ck_operator_status;
ALTER TABLE operator_account DROP COLUMN disabled_at;
ALTER TABLE operator_account ALTER COLUMN status DROP DEFAULT;
ALTER TABLE operator_account ADD CONSTRAINT operator_account_kakao_provider_key_key
    UNIQUE USING INDEX uq_operator_kakao_provider_key;

ALTER TABLE office_registration_status_history
    DROP CONSTRAINT fk_reg_history_operator;
ALTER TABLE office_registration_status_history ADD CONSTRAINT fk_registration_history_operator
    FOREIGN KEY (changed_by_operator_id) REFERENCES operator_account (id);

COMMENT ON TABLE operator_account IS '사이로팀 운영자 로그인 전용 계정. app_user·office_membership과 분리되며 사무소 소속이 없다. 화이트리스트에 등록된 카카오 계정만 운영자 세션을 받는다';
COMMENT ON COLUMN operator_account.kakao_provider_key IS '운영자 본인의 카카오 고유 회원번호(화이트리스트 매칭 전용)';
COMMENT ON COLUMN operator_account.status IS '계정 상태: ACTIVE 로그인 가능, DISABLED 로그인 차단';

ALTER TABLE rate_limit_counter DROP CONSTRAINT ck_rate_limit_counter_count;
ALTER TABLE rate_limit_counter ADD CHECK (request_count >= 0);
ALTER TABLE rate_limit_counter ALTER COLUMN request_count DROP DEFAULT;

DROP INDEX ix_rate_limit_counter_window_started_at;

-- ---------------------------------------------------------------------
-- office_membership, office_membership_status_history
-- ---------------------------------------------------------------------
-- property·property_contract·coordination의 (member_id, office_id) 복합 FK가 모두 정리된
-- 뒤에야 office_membership_member_id_office_id_key 인덱스를 안전하게 재구성할 수 있다.

ALTER TABLE office_membership DROP CONSTRAINT ck_office_membership_reviewer;
ALTER TABLE office_membership DROP CONSTRAINT fk_membership_reviewed_by;
ALTER TABLE office_membership DROP CONSTRAINT office_membership_reviewed_by_type_check;
ALTER TABLE office_membership DROP CONSTRAINT ck_office_membership_status;
ALTER TABLE office_membership DROP COLUMN reviewed_by_type;
ALTER TABLE office_membership DROP COLUMN reviewed_by_membership_id;

ALTER TABLE office_membership RENAME COLUMN reapplication_started_at TO resolved_at;

ALTER TABLE office_membership DROP CONSTRAINT office_membership_office_id_fkey;
ALTER TABLE office_membership ADD FOREIGN KEY (office_id) REFERENCES office (id);

ALTER TABLE office_membership RENAME COLUMN member_id TO user_id;
ALTER TABLE office_membership DROP CONSTRAINT office_membership_member_id_fkey;
ALTER TABLE office_membership ADD FOREIGN KEY (user_id) REFERENCES app_user (id);
ALTER TABLE office_membership DROP CONSTRAINT office_membership_id_member_id_office_id_key;
ALTER TABLE office_membership DROP CONSTRAINT office_membership_member_id_office_id_key;
ALTER TABLE office_membership ADD CONSTRAINT office_membership_office_id_user_id_key UNIQUE (office_id, user_id);
ALTER TABLE office_membership ALTER COLUMN status DROP DEFAULT;
ALTER TABLE office_membership DROP COLUMN created_at;

ALTER TABLE office_membership DROP CONSTRAINT office_membership_role_check;
ALTER TABLE office_membership ADD CONSTRAINT office_membership_role_check CHECK (role IN ('STAFF','ADMIN'));

ALTER TABLE office_membership DROP CONSTRAINT office_membership_status_check;
ALTER TABLE office_membership ADD CONSTRAINT office_membership_status_check
    CHECK (status IN ('APPROVED','PENDING','REJECTED','REVOKED','CANCELLED'));

ALTER TABLE office_membership ADD CONSTRAINT ck_office_membership_status CHECK (
    (status = 'PENDING'
        AND role = 'STAFF' AND reviewed_at IS NULL AND rejection_reason IS NULL
        AND revoked_at IS NULL AND revocation_reason IS NULL AND cancelled_at IS NULL AND resolved_at IS NULL)
    OR (status = 'APPROVED'
        AND reviewed_at IS NOT NULL AND rejection_reason IS NULL
        AND revoked_at IS NULL AND revocation_reason IS NULL AND cancelled_at IS NULL AND resolved_at IS NULL)
    OR (status = 'REJECTED'
        AND reviewed_at IS NOT NULL AND rejection_reason IS NOT NULL
        AND revoked_at IS NULL AND revocation_reason IS NULL AND cancelled_at IS NULL)
    OR (status = 'REVOKED'
        AND reviewed_at IS NOT NULL AND rejection_reason IS NULL
        AND revoked_at IS NOT NULL AND revocation_reason IS NOT NULL AND cancelled_at IS NULL AND resolved_at IS NULL)
    OR (status = 'CANCELLED'
        AND reviewed_at IS NULL AND rejection_reason IS NULL
        AND revoked_at IS NULL AND revocation_reason IS NULL AND cancelled_at IS NOT NULL AND resolved_at IS NULL)
);

DROP INDEX ix_office_membership_member_status;
ALTER INDEX uq_office_membership_member_approved RENAME TO uq_user_approved_office;
ALTER INDEX uq_office_membership_member_pending RENAME TO uq_user_pending_office;

COMMENT ON TABLE office_membership IS '사용자와 사무소의 소속·권한';
COMMENT ON COLUMN office_membership.user_id IS '소속된 사용자';
COMMENT ON COLUMN office_membership.role IS '역할: STAFF 일반직원, ADMIN 관리자(사무소당 여러 명 가능). 최초 ADMIN은 신규 사무소 승인 시 자동 지정되고, 이후 늘리는 유일한 경로는 기존 ADMIN의 권한 부여 명령이다';
COMMENT ON COLUMN office_membership.resolved_at IS '재신청 시작으로 이 거절 건이 해결됨 처리된 일시. REJECTED에서만 값이 있을 수 있고, 값이 있으면 AuthContext 판정에서 미해결로 취급하지 않는다. REVOKED는 재신청 개념이 없어 항상 NULL';
COMMENT ON INDEX uq_user_approved_office IS '사용자당 APPROVED 사무소 소속은 최대 1개';
COMMENT ON INDEX uq_user_pending_office IS '사용자당 진행 중(PENDING) 참여 요청 최대 1개. 동시 등록·참여 요청 1건 제한 정책의 절반';

ALTER TABLE office_membership_status_history DROP CONSTRAINT ck_membership_history_actor;
ALTER TABLE office_membership_status_history DROP CONSTRAINT fk_membership_history_parent;
ALTER TABLE office_membership_status_history DROP CONSTRAINT fk_membership_history_changed_by;

ALTER TABLE office_membership_status_history RENAME COLUMN office_membership_id TO membership_id;
ALTER TABLE office_membership_status_history DROP COLUMN office_id;
ALTER TABLE office_membership_status_history ALTER COLUMN from_status TYPE VARCHAR(20);
ALTER TABLE office_membership_status_history ALTER COLUMN to_status TYPE VARCHAR(20);

ALTER TABLE office_membership_status_history DROP CONSTRAINT office_membership_status_history_from_status_check;
ALTER TABLE office_membership_status_history ADD CONSTRAINT office_membership_status_history_from_status_check
    CHECK (from_status IN ('APPROVED','PENDING','REJECTED','REVOKED','CANCELLED'));

ALTER TABLE office_membership_status_history DROP CONSTRAINT office_membership_status_history_to_status_check;
ALTER TABLE office_membership_status_history ADD CONSTRAINT office_membership_status_history_to_status_check
    CHECK (to_status IN ('APPROVED','PENDING','REJECTED','REVOKED','CANCELLED'));

ALTER TABLE office_membership_status_history ADD FOREIGN KEY (membership_id) REFERENCES office_membership (id);
ALTER TABLE office_membership_status_history ADD FOREIGN KEY (changed_by_membership_id) REFERENCES office_membership (id);

ALTER TABLE office_membership_status_history ADD CONSTRAINT ck_membership_history_actor CHECK (
    (changed_by_type = 'ADMIN' AND changed_by_membership_id IS NOT NULL AND changed_by_membership_id <> membership_id)
    OR (changed_by_type IN ('APPLICANT','SYSTEM') AND changed_by_membership_id IS NULL)
);

COMMENT ON TABLE office_membership_status_history IS '사무소 소속 상태 변경 이력(관리자 권한 부여·회수, 승인·거절·회수 포함)';
COMMENT ON COLUMN office_membership_status_history.membership_id IS '대상 소속';
COMMENT ON COLUMN office_membership_status_history.changed_by_membership_id IS '변경을 실행한 관리자 소속. 자기 자신을 대상으로는 실행할 수 없다(소속 회수 자기 자신 대상 금지)';
