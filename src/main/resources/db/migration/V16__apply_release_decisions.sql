-- Brings the V15 schema (140 tables) to the final release schema (141 tables).
-- 1. Replaces constraints on existing tables, renames and widens one column, adds columns,
--    then adds the new constraints (drop, rename, add column, add constraint order).
-- 2. Creates the notification_delivery table.
-- 3. Adds COMMENT statements for the new and changed tables and columns.
-- V15 and earlier are not modified. No rows are inserted, updated or deleted.

-- ---------------------------------------------------------------------
-- 1. Existing tables: drop replaced constraints
-- ---------------------------------------------------------------------
ALTER TABLE notification DROP CONSTRAINT notification_notification_type_check;
ALTER TABLE notification DROP CONSTRAINT notification_target_type_check;
ALTER TABLE ops_raw_access_grant DROP CONSTRAINT ops_raw_access_grant_check;
ALTER TABLE export_job DROP CONSTRAINT export_job_scope_check;
ALTER TABLE export_job DROP CONSTRAINT ck_export_job_scope_tables;
ALTER TABLE visit_coordination DROP CONSTRAINT visit_coordination_reschedule_trigger_type_check;
ALTER TABLE visit_request_revalidation DROP CONSTRAINT visit_request_revalidation_failure_code_check;

-- ---------------------------------------------------------------------
-- 1. Existing tables: rename and widen the approver phone column
-- ---------------------------------------------------------------------
ALTER TABLE property_access_approval RENAME COLUMN approver_phone TO approver_phone_encrypted;
ALTER TABLE property_access_approval ALTER COLUMN approver_phone_encrypted TYPE VARCHAR(255);

-- ---------------------------------------------------------------------
-- 1. Existing tables: add columns
-- ---------------------------------------------------------------------
ALTER TABLE visit_assistant_notice_log ADD COLUMN supervisor_additional_notice TEXT;
ALTER TABLE visit_assistant_notice_log ADD COLUMN supervisor_additional_noticed_at TIMESTAMPTZ;
ALTER TABLE ops_approval_request ADD COLUMN requested_expires_minutes INT;
ALTER TABLE legal_authority_request ADD COLUMN close_note TEXT;
ALTER TABLE privacy_request ADD COLUMN version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE deletion_queue ADD COLUMN verification_evidence TEXT;
ALTER TABLE export_job ADD COLUMN archive_grant_id BIGINT;
ALTER TABLE office ADD COLUMN closed_at TIMESTAMPTZ;

-- ---------------------------------------------------------------------
-- 1. Existing tables: existing rows
-- ---------------------------------------------------------------------
-- approver_phone_encrypted keeps the existing values as they are. A backend batch replaces them
-- with ciphertext using the application encryption key, which SQL migrations do not hold.
-- privacy_request.version takes its column default 1. All other new columns are NULL.

-- ---------------------------------------------------------------------
-- 1. Existing tables: add constraints
-- ---------------------------------------------------------------------
ALTER TABLE notification ADD CONSTRAINT notification_notification_type_check CHECK (notification_type IN ('OFFICE_REVIEW','MEMBERSHIP_APPROVAL_NEEDED','INVITATION','ASSIGNEE_CHANGED','VISIT_SCHEDULED','VISIT_CONFIRMED','VISIT_RESCHEDULE_NEEDED','VISIT_COMPLETION_PENDING','VISIT_SUPERVISION_CHECK','SECURITY_ALERT','NOTICE','TRIAL_ENDING','PLAN_CHANGED','PAYMENT','RETURN_SCHEDULED','DELETION_SCHEDULED','SUPPORT_REPLY','TERMS_CHANGED'));
ALTER TABLE notification ADD CONSTRAINT notification_target_type_check CHECK (target_type IN ('OFFICE_REGISTRATION','MEMBERSHIP','VISIT_COORDINATION','PROPERTY','CUSTOMER','CONTRACT','SUBSCRIPTION','DATA_RETURN','SUPPORT_TICKET','NONE'));
ALTER TABLE ops_raw_access_grant ADD CONSTRAINT ops_raw_access_grant_check CHECK (expires_at > executed_at AND expires_at <= executed_at + interval '60 minutes');
ALTER TABLE export_job ADD CONSTRAINT export_job_scope_check CHECK (scope IN ('FULL','TABLES','ARCHIVE'));
ALTER TABLE export_job ADD CONSTRAINT ck_export_job_scope_tables
    CHECK ((scope IN ('FULL','ARCHIVE') AND table_codes IS NULL)
        OR (scope = 'TABLES' AND table_codes IS NOT NULL AND jsonb_typeof(table_codes) = 'array' AND jsonb_array_length(table_codes) > 0
            AND table_codes <@ '["PROPERTY","CUSTOMER","CONTRACT","FEE","PAYMENT","TASK","VISIT"]'::jsonb));
ALTER TABLE export_job ADD CONSTRAINT ck_export_job_scope_archive CHECK ((scope = 'ARCHIVE') = (archive_grant_id IS NOT NULL));
ALTER TABLE export_job ADD CONSTRAINT fk_export_job_archive_grant FOREIGN KEY (archive_grant_id, office_id) REFERENCES archive_grant (id, office_id);
ALTER TABLE visit_coordination ADD CONSTRAINT visit_coordination_reschedule_trigger_type_check CHECK (reschedule_trigger_type IN ('SCHEDULE_CHANGED','ACCESS_REVOKED','OCCUPANT_CHANGED','ASSIGNEE_HANDOVER'));
ALTER TABLE visit_request_revalidation ADD CONSTRAINT visit_request_revalidation_failure_code_check CHECK (failure_code IS NULL OR failure_code IN ('CONDITION_EXPIRED','CONDITION_CHANGED','NO_PERMISSION','WINDOW_MISMATCH','MAX_VISITS_PER_DAY','MIN_GAP','MIN_LEAD','SINGLE_VISIT_MAX','CUSTOMER_CONFLICT','BROKER_CONFLICT','PROPERTY_CONFLICT','TRAVEL_CONFLICT','DEPUTY_VISIT_LOCKED'));
ALTER TABLE visit_assistant_notice_log ADD CONSTRAINT ck_visit_assistant_notice_log_additional_notice CHECK ((supervisor_additional_notice IS NULL) = (supervisor_additional_noticed_at IS NULL));
ALTER TABLE ops_approval_request ADD CONSTRAINT ck_ops_approval_request_requested_expires_minutes CHECK (requested_expires_minutes IS NULL OR requested_expires_minutes BETWEEN 1 AND 60);
ALTER TABLE privacy_request ADD CONSTRAINT privacy_request_version_check CHECK (version > 0);

-- ---------------------------------------------------------------------
-- 2. New tables (1)
-- ---------------------------------------------------------------------
CREATE TABLE notification_delivery (
    id                            BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    notification_id               BIGINT NOT NULL REFERENCES notification(id),
    channel                       VARCHAR(10) NOT NULL CHECK (channel IN ('EMAIL','SMS')),
    status                        VARCHAR(10) NOT NULL CHECK (status IN ('PENDING','DELIVERED','FAILED','ABANDONED')),
    attempt_count                 INT NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at               TIMESTAMPTZ,
    last_attempted_at             TIMESTAMPTZ,
    last_error_code               VARCHAR(80),
    delivered_at                  TIMESTAMPTZ,
    created_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (notification_id, channel),
    CONSTRAINT ck_notification_delivery_delivered CHECK ((status = 'DELIVERED') = (delivered_at IS NOT NULL)),
    CONSTRAINT ck_notification_delivery_pending CHECK (status <> 'PENDING' OR next_attempt_at IS NOT NULL)
);

-- ---------------------------------------------------------------------
-- 3. COMMENT statements for the new and changed tables and columns
-- ---------------------------------------------------------------------
COMMENT ON TABLE visit_assistant_notice_log IS '중개보조원 배정 방문의 법정 신분 고지 기록. 고지는 원칙적으로 안내 전에 기록하며, 사후입력은 recorded_after_the_fact로 구분 표시하고 누락 시 missing_exception_reason이 필요하다. 감독 공인중개사가 confirmed_by_membership_id로 확인하며 확인 때 남긴 추가 안내는 supervisor_additional_notice에 저장한다';
COMMENT ON COLUMN visit_assistant_notice_log.supervisor_additional_notice IS '감독 공인중개사가 감독 확인 때 남긴 추가 안내다. 추가 안내가 없으면 NULL이다';
COMMENT ON COLUMN visit_assistant_notice_log.supervisor_additional_noticed_at IS '추가 안내를 남긴 시각이다. 서버가 감독 확인 시각으로 채우며 추가 안내가 있을 때만 값이 있다';
COMMENT ON TABLE notification IS '알림함 알림이다. 수신자 본인 알림만 최근 발생순으로 조회한다. 읽음 여부는 read_at이 있는지로 판정하고 unreadOnly 조회와 미읽음 수 집계는 read_at이 비어 있는 행을 쓴다. notification_type은 OFFICE_REVIEW(심사), MEMBERSHIP_APPROVAL_NEEDED(참여 승인 필요), INVITATION(초대), ASSIGNEE_CHANGED(담당 변경), VISIT_SCHEDULED(방문 일정), VISIT_CONFIRMED(방문 확정), VISIT_RESCHEDULE_NEEDED(재조율 필요), VISIT_COMPLETION_PENDING(방문 완료 처리 대기), VISIT_SUPERVISION_CHECK(감독 확인), SECURITY_ALERT(로그인·보안), NOTICE(공지·혜택), TRIAL_ENDING(체험 종료), PLAN_CHANGED(요금 변경), PAYMENT(결제), RETURN_SCHEDULED(반환 예정), DELETION_SCHEDULED(삭제 예정), SUPPORT_REPLY(지원 답변), TERMS_CHANGED(약관 변경)의 열여덟 가지다. TERMS_CHANGED는 약관과 개인정보처리방침의 변경 고지이며 수신 설정과 무관하게 보낸다. target_type은 눌렀을 때 이동할 화면의 대상 종류이고 target_id가 가리키는 표는 target_type으로 판별한다. 알림 수신 설정은 app_user_notification_preference가 맡고 외부 채널의 전달 상태와 재시도는 notification_delivery가 맡는다';
COMMENT ON COLUMN notification.notification_type IS '알림 종류다. 열여덟 가지 코드 중 하나이며 코드별 의미는 표 설명을 따른다';
COMMENT ON COLUMN notification.target_type IS '눌렀을 때 이동할 화면의 대상 종류다. OFFICE_REGISTRATION, MEMBERSHIP, VISIT_COORDINATION, PROPERTY, CUSTOMER, CONTRACT, SUBSCRIPTION, DATA_RETURN, SUPPORT_TICKET 중 하나이고 이동 대상이 없으면 NONE이다';
COMMENT ON TABLE notification_delivery IS '알림의 외부 채널 전달 상태다. 알림 한 건을 외부 채널 하나로 전달하는 일마다 한 행을 두며 같은 알림의 같은 채널은 한 행이다. 전달에 실패하면 5분, 30분, 2시간 뒤에 재시도하고 세 번의 재시도가 모두 실패하면 ABANDONED로 두어 운영 콘솔의 시스템 작업에 실패로 드러낸다. 재시도 간격과 횟수는 서비스 계층이 관리한다. 알림함 표시와 읽음 상태는 notification이 맡는다';
COMMENT ON COLUMN notification_delivery.id IS '알림 전달 내부 식별자';
COMMENT ON COLUMN notification_delivery.notification_id IS '전달 대상 알림';
COMMENT ON COLUMN notification_delivery.channel IS '전달 채널이다. EMAIL은 메일, SMS는 문자다';
COMMENT ON COLUMN notification_delivery.status IS '전달 상태다. PENDING은 전달 대기, DELIVERED는 전달 완료, FAILED는 전달 실패, ABANDONED는 재시도를 모두 실패해 전달을 포기한 상태다';
COMMENT ON COLUMN notification_delivery.attempt_count IS '전달 시도 횟수';
COMMENT ON COLUMN notification_delivery.next_attempt_at IS '다음 전달 시도 예정 시각이다. PENDING이면 반드시 값이 있다';
COMMENT ON COLUMN notification_delivery.last_attempted_at IS '마지막으로 전달을 시도한 시각이다. 시도 전에는 NULL이다';
COMMENT ON COLUMN notification_delivery.last_error_code IS '마지막 전달 실패 코드다';
COMMENT ON COLUMN notification_delivery.delivered_at IS '전달을 마친 시각이다. DELIVERED일 때만 값이 있다';
COMMENT ON COLUMN notification_delivery.created_at IS '전달 대상으로 만든 시각';
COMMENT ON COLUMN notification_delivery.updated_at IS '전달 상태를 마지막으로 바꾼 일시';
COMMENT ON COLUMN ops_approval_request.requested_expires_minutes IS '원문 접근 요청자가 요청한 접근 창의 길이다. 단위는 분이고 1 이상 60 이하다. 값이 없으면 60분으로 본다. 원문 접근을 실행할 때 접근 창을 이 길이만큼 연다';
COMMENT ON TABLE ops_raw_access_grant IS '승인된 원문 접근을 실제로 실행한 기록이다. 5분 재인증 후 1회 실행한다. 원문 접근 창은 승인 요청이 요청한 분만큼 열고 요청 값이 없으면 60분이며 60분을 넘을 수 없다. 운영 승인 유효기간 24시간과는 별개의 타이머다. 열람 승인과 다운로드 승인은 별개이며 열람 승인으로는 파일을 받을 수 없다. 승인 1건은 1회만 실행하며 다운로드 파일은 실행한 운영자가 접근 창 안에 한 번만 받을 수 있다';
COMMENT ON COLUMN ops_raw_access_grant.expires_at IS '원문 접근 창이 끝나는 시각이다. 실행 시각보다 뒤이고 실행 시각에서 60분 이내다';
COMMENT ON COLUMN legal_authority_request.close_note IS '요청을 종결할 때 남긴 메모다';
COMMENT ON COLUMN privacy_request.version IS '권리 요청 집계의 낙관적 잠금 버전';
COMMENT ON COLUMN deletion_queue.verification_evidence IS '파기 결과를 검증한 운영자가 남긴 검증 증빙이다';
COMMENT ON TABLE export_job IS '자료 내보내기 작업이다. scope가 FULL이면 전체 자료와 첨부를 ZIP으로 만들며 대표만 요청할 수 있다. TABLES이면 table_codes에 담은 표만 내보내며 소속공인중개사는 office_export_table_policy가 허용한 표만 요청할 수 있다. ARCHIVE이면 보관권한자가 보관 규칙이 법정 보존으로 지정한 계약 서류와 거래 기록을 내보내며 archive_grant_id가 근거 보관권한을 가리킨다. 생성 파일은 file_expires_at에 삭제하고 다운로드 URL은 저장하지 않으며 매 다운로드 요청마다 권한을 다시 검사한다. 표 코드의 전체 집합은 PROPERTY, CUSTOMER, CONTRACT, FEE, PAYMENT, TASK, VISIT 일곱 가지다';
COMMENT ON COLUMN export_job.scope IS '내보내기 범위다. FULL은 전체 자료와 첨부 ZIP으로 대표만 요청할 수 있고 TABLES는 table_codes에 담은 표만 내보낸다. ARCHIVE는 보관권한으로 법정 보존 문서를 내보낸다';
COMMENT ON COLUMN export_job.table_codes IS 'TABLES 범위의 대상 표 코드 목록이다. PROPERTY, CUSTOMER, CONTRACT, FEE, PAYMENT, TASK, VISIT 중 하나 이상을 담는 JSON 배열이다. FULL과 ARCHIVE이면 NULL이다';
COMMENT ON COLUMN export_job.archive_grant_id IS '보관권한 문서 내보내기의 근거 보관권한이다. scope가 ARCHIVE일 때만 값이 있고 같은 사무소의 보관권한이어야 한다';
COMMENT ON COLUMN property_access_approval.approver_phone_encrypted IS '승인자 연락처를 애플리케이션이 암호화한 문자열이다. 연락처 원문은 저장하지 않고 번호 대조는 approver_phone_hash로 한다. DIRECT 승인은 필수이고 PROXY 대리입력은 NULL을 허용한다. 익명화하면 DIRECT 승인도 NULL이 된다';
COMMENT ON COLUMN office.closed_at IS '사무소를 종료 처리한 시각이다. 값이 있으면 종료된 사무소이며 사무소 검색과 참여 요청 대상에서 빠진다. 종료하지 않은 사무소는 NULL이다';
COMMENT ON COLUMN visit_coordination.reschedule_trigger_type IS '재조율 경보를 일으킨 원인이다. SCHEDULE_CHANGED는 일정 변경, ACCESS_REVOKED는 출입승인 철회, OCCUPANT_CHANGED는 점유자 변경, ASSIGNEE_HANDOVER는 담당 인계다. 조율현황이 RESCHEDULE_REQUIRED일 때만 값이 있고 다른 상태로 옮기면 비운다. 상태 전이 사실은 visit_coordination_status_history에 남는다';
COMMENT ON COLUMN visit_request_revalidation.failure_code IS 'FAILED일 때만 값 존재. 3장의 13개 값 중 하나이며 MIN_LEAD는 신청 제출 검증에서만 쓰므로 재검증 기록에는 남지 않는다. DEPUTY_VISIT_LOCKED는 신청 담당자가 중개보조원이고 신청 사무소가 중개보조원 고용 한도를 넘어 방문업무가 잠긴 경우다';
