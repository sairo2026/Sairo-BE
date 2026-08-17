package com.sairo.be.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SchemaMigrationTest extends AbstractSchemaTest {

    private static final List<String> DOMAIN_TABLES = List.of(
            "member", "login_identity", "login_history", "email_verification_code",
            "office", "office_registration", "office_registration_status_history", "document_move_outbox",
            "office_membership", "office_membership_status_history",
            "property", "property_contract",
            "coordination", "coordination_candidate_time", "coordination_status_history",
            "expiry_task", "shedlock",
            "terms_catalog", "terms_agreement", "oauth_transaction", "kakao_signup_ticket", "rate_limit_counter"
    );

    @Autowired
    private Flyway flyway;

    @Test
    void flyway_validate_통과한다() {
        flyway.validate();
    }

    @Test
    void V1부터_V8까지_여덟_마이그레이션이_모두_성공했다() throws Exception {
        withRollback(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")) {
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> versions = new java.util.ArrayList<>();
                    while (rs.next()) {
                        assertThat(rs.getBoolean("success")).as("version %s는 성공해야 함", rs.getString("version")).isTrue();
                        versions.add(rs.getString("version"));
                    }
                    assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
                }
            }
        });
    }

    @Test
    void 도메인_테이블_22개가_전부_존재한다() throws Exception {
        withRollback(conn -> assertThat(countTables(conn)).isEqualTo(22));
    }

    // 참조 DDL에서 ALTER TABLE ... ADD CONSTRAINT로 명시적으로 이름 붙인 제약 46개.
    // PK 인라인 정의, 컬럼 인라인 CHECK/REFERENCES, NOT NULL의 PostgreSQL 내부 표현은 이 목록에 포함하지 않는다.
    // 총 개수(information_schema.table_constraints 기준 240개)는 그 내부 표현까지 섞인 값이라 여기서는 쓰지 않는다 — PostgreSQL 버전이나 내부 표현이 바뀌면 총 개수는 흔들릴 수 있지만, 아래 이름별 정의는 흔들리지 않는다.
    private static final List<NamedObject> NAMED_CONSTRAINTS = List.of(
            new NamedObject("coordination", "ck_coordination_confirmation_pair", "CHECK (((confirmed_candidate_time_id IS NULL) = (confirmed_at IS NULL)))"),
            new NamedObject("coordination", "ck_coordination_origin_not_self", "CHECK (((origin_coordination_id IS NULL) OR (origin_coordination_id <> id)))"),
            new NamedObject("coordination", "ck_coordination_status_columns", "CHECK (((((status)::text = 'PENDING_RESPONSE'::text) AND (last_responded_at IS NULL) AND (confirmed_at IS NULL) AND (completed_at IS NULL) AND (cancelled_at IS NULL) AND (rebooking_requested_at IS NULL)) OR (((status)::text = 'RESPONDED'::text) AND (last_responded_at IS NOT NULL) AND (confirmed_at IS NULL) AND (completed_at IS NULL) AND (cancelled_at IS NULL) AND (rebooking_requested_at IS NULL)) OR (((status)::text = 'CONFIRMED'::text) AND (confirmed_at IS NOT NULL) AND (completed_at IS NULL) AND (cancelled_at IS NULL) AND (rebooking_requested_at IS NULL)) OR (((status)::text = 'COMPLETED'::text) AND (confirmed_at IS NOT NULL) AND (completed_at IS NOT NULL) AND (cancelled_at IS NULL) AND (rebooking_requested_at IS NULL)) OR (((status)::text = 'CANCELLED'::text) AND (cancelled_at IS NOT NULL) AND (completed_at IS NULL) AND (rebooking_requested_at IS NULL)) OR (((status)::text = 'NEEDS_REBOOKING'::text) AND (rebooking_requested_at IS NOT NULL) AND (confirmed_at IS NULL) AND (completed_at IS NULL) AND (cancelled_at IS NULL))))"),
            new NamedObject("coordination", "fk_coordination_confirmed_time", "FOREIGN KEY (confirmed_candidate_time_id, id) REFERENCES coordination_candidate_time(id, coordination_id) ON DELETE RESTRICT"),
            new NamedObject("coordination", "fk_coordination_created_by_membership", "FOREIGN KEY (created_by_member_id, office_id) REFERENCES office_membership(member_id, office_id) ON DELETE RESTRICT"),
            new NamedObject("coordination", "fk_coordination_origin_office", "FOREIGN KEY (origin_coordination_id, office_id) REFERENCES coordination(id, office_id) ON DELETE RESTRICT"),
            new NamedObject("coordination", "fk_coordination_property_office", "FOREIGN KEY (property_id, office_id) REFERENCES property(id, office_id) ON DELETE RESTRICT"),
            new NamedObject("coordination_status_history", "ck_status_history_actor", "CHECK (((((change_source)::text = 'OFFICE_MEMBER'::text) AND (changed_by_member_id IS NOT NULL)) OR (((change_source)::text = ANY ((ARRAY['CUSTOMER_LINK'::character varying, 'SYSTEM_BATCH'::character varying])::text[])) AND (changed_by_member_id IS NULL))))"),
            new NamedObject("coordination_status_history", "fk_status_history_changed_by_membership", "FOREIGN KEY (changed_by_member_id, office_id) REFERENCES office_membership(member_id, office_id) ON DELETE RESTRICT"),
            new NamedObject("coordination_status_history", "fk_status_history_parent", "FOREIGN KEY (coordination_id, office_id) REFERENCES coordination(id, office_id) ON DELETE RESTRICT"),
            new NamedObject("document_move_outbox", "ck_outbox_status", "CHECK (((((status)::text = 'PENDING'::text) AND (locked_at IS NULL) AND (locked_by IS NULL) AND (lock_token IS NULL) AND (lock_expires_at IS NULL) AND (processed_at IS NULL)) OR (((status)::text = 'IN_PROGRESS'::text) AND (locked_at IS NOT NULL) AND (locked_by IS NOT NULL) AND (lock_token IS NOT NULL) AND (lock_expires_at IS NOT NULL) AND (processed_at IS NULL)) OR (((status)::text = 'DONE'::text) AND (processed_at IS NOT NULL) AND (locked_at IS NULL) AND (locked_by IS NULL) AND (lock_token IS NULL) AND (lock_expires_at IS NULL)) OR (((status)::text = 'FAILED'::text) AND (last_error IS NOT NULL) AND (locked_at IS NULL) AND (locked_by IS NULL) AND (lock_token IS NULL) AND (lock_expires_at IS NULL))))"),
            new NamedObject("email_verification_code", "ck_email_verification_consumed", "CHECK (((consumed_at IS NULL) OR (((status)::text = 'VERIFIED'::text) AND ((purpose)::text = ANY ((ARRAY['SIGNUP'::character varying, 'LOGIN'::character varying, 'EMAIL_CHANGE'::character varying])::text[])))))"),
            new NamedObject("email_verification_code", "ck_email_verification_status", "CHECK (((((status)::text = 'ACTIVE'::text) AND (verified_at IS NULL) AND (invalidated_at IS NULL)) OR (((status)::text = 'VERIFIED'::text) AND (verified_at IS NOT NULL) AND (invalidated_at IS NULL)) OR (((status)::text = ANY ((ARRAY['REPLACED'::character varying, 'LOCKED'::character varying, 'EXPIRED'::character varying])::text[])) AND (verified_at IS NULL) AND (invalidated_at IS NOT NULL))))"),
            new NamedObject("expiry_task", "ck_expiry_task_status", "CHECK (((((status)::text = 'OPEN'::text) AND (completed_at IS NULL) AND (cancelled_at IS NULL) AND (cancel_reason IS NULL)) OR (((status)::text = 'COMPLETED'::text) AND (completed_at IS NOT NULL) AND (cancelled_at IS NULL) AND (cancel_reason IS NULL)) OR (((status)::text = 'CANCELLED'::text) AND (completed_at IS NULL) AND (cancelled_at IS NOT NULL) AND (cancel_reason IS NOT NULL))))"),
            new NamedObject("expiry_task", "ck_expiry_task_target_date", "CHECK ((target_date = (contract_end_date - 90)))"),
            new NamedObject("expiry_task", "ck_expiry_task_type", "CHECK (((task_type)::text = 'D90'::text))"),
            new NamedObject("expiry_task", "fk_expiry_task_contract_office_property", "FOREIGN KEY (property_contract_id, office_id, property_id, deal_type) REFERENCES property_contract(id, office_id, property_id, deal_type) ON DELETE RESTRICT"),
            new NamedObject("kakao_signup_ticket", "ck_kakao_signup_ticket_expires", "CHECK ((expires_at > created_at))"),
            new NamedObject("kakao_signup_ticket", "ck_kakao_signup_ticket_used", "CHECK (((used_at IS NULL) OR (used_at >= created_at)))"),
            new NamedObject("login_history", "fk_login_history_identity_member", "FOREIGN KEY (login_identity_id, member_id) REFERENCES login_identity(id, member_id) ON DELETE RESTRICT"),
            new NamedObject("login_identity", "uq_login_identity_id_member", "UNIQUE (id, member_id)"),
            new NamedObject("member", "ck_member_withdrawn", "CHECK ((((account_status)::text = 'WITHDRAWN'::text) = (withdrawn_at IS NOT NULL)))"),
            new NamedObject("oauth_transaction", "ck_oauth_transaction_expires", "CHECK ((expires_at > created_at))"),
            new NamedObject("oauth_transaction", "ck_oauth_transaction_purpose_fields", "CHECK (((((purpose)::text = 'LINK'::text) AND (member_id IS NOT NULL) AND (initiating_session_id IS NOT NULL) AND (step_up_verified_at IS NOT NULL)) OR (((purpose)::text = 'LOGIN'::text) AND (member_id IS NULL) AND (initiating_session_id IS NULL) AND (step_up_verified_at IS NULL))))"),
            new NamedObject("oauth_transaction", "ck_oauth_transaction_used", "CHECK (((used_at IS NULL) OR (used_at >= created_at)))"),
            new NamedObject("office_membership", "ck_office_membership_reviewer", "CHECK (((reviewed_by_type IS NULL) OR (((reviewed_by_type)::text = 'ADMIN'::text) AND (reviewed_by_membership_id IS NOT NULL) AND (reviewed_by_operator_identifier IS NULL)) OR (((reviewed_by_type)::text = 'OPERATOR'::text) AND (reviewed_by_membership_id IS NULL) AND (reviewed_by_operator_identifier IS NOT NULL)) OR (((reviewed_by_type)::text = 'SYSTEM'::text) AND (reviewed_by_membership_id IS NULL) AND (reviewed_by_operator_identifier IS NULL))))"),
            new NamedObject("office_membership", "ck_office_membership_status", "CHECK (((((status)::text = 'PENDING'::text) AND (reviewed_at IS NULL) AND (reviewed_by_type IS NULL) AND (reviewed_by_membership_id IS NULL) AND (reviewed_by_operator_identifier IS NULL) AND (rejection_reason IS NULL) AND (revoked_at IS NULL) AND (revocation_reason IS NULL)) OR (((status)::text = 'APPROVED'::text) AND (reviewed_at IS NOT NULL) AND (reviewed_by_type IS NOT NULL) AND (rejection_reason IS NULL) AND (revoked_at IS NULL) AND (revocation_reason IS NULL)) OR (((status)::text = 'REJECTED'::text) AND (reviewed_at IS NOT NULL) AND (reviewed_by_type IS NOT NULL) AND (rejection_reason IS NOT NULL) AND (revoked_at IS NULL) AND (revocation_reason IS NULL)) OR (((status)::text = 'REVOKED'::text) AND (reviewed_at IS NOT NULL) AND (reviewed_by_type IS NOT NULL) AND (rejection_reason IS NULL) AND (revoked_at IS NOT NULL) AND (revocation_reason IS NOT NULL))))"),
            new NamedObject("office_membership", "fk_membership_reviewed_by", "FOREIGN KEY (reviewed_by_membership_id, office_id) REFERENCES office_membership(id, office_id) ON DELETE RESTRICT"),
            new NamedObject("office_membership_status_history", "ck_membership_history_actor", "CHECK (((((changed_by_type)::text = 'ADMIN'::text) AND (changed_by_membership_id IS NOT NULL) AND ((changed_by_membership_id <> office_membership_id) OR (((from_status)::text = 'APPROVED'::text) AND ((to_status)::text = 'REVOKED'::text)))) OR (((changed_by_type)::text = ANY ((ARRAY['APPLICANT'::character varying, 'SYSTEM'::character varying])::text[])) AND (changed_by_membership_id IS NULL))))"),
            new NamedObject("office_membership_status_history", "fk_membership_history_changed_by", "FOREIGN KEY (changed_by_membership_id, office_id) REFERENCES office_membership(id, office_id) ON DELETE RESTRICT"),
            new NamedObject("office_membership_status_history", "fk_membership_history_parent", "FOREIGN KEY (office_membership_id, office_id) REFERENCES office_membership(id, office_id) ON DELETE RESTRICT"),
            new NamedObject("office_registration", "ck_office_registration_status", "CHECK (((((status)::text = ANY ((ARRAY['PENDING_VERIFICATION'::character varying, 'MANUAL_REVIEW'::character varying])::text[])) AND (resulting_office_id IS NULL) AND (rejection_reason IS NULL) AND (reviewed_at IS NULL)) OR (((status)::text = 'APPROVED'::text) AND (resulting_office_id IS NOT NULL) AND (rejection_reason IS NULL) AND (reviewed_at IS NOT NULL)) OR (((status)::text = 'REJECTED'::text) AND (resulting_office_id IS NULL) AND (rejection_reason IS NOT NULL) AND (reviewed_at IS NOT NULL))))"),
            new NamedObject("office_registration", "ck_office_registration_storage", "CHECK ((((status)::text <> 'REJECTED'::text) OR (document_object_key IS NULL) OR ((document_storage_status)::text = ANY ((ARRAY['MOVE_PENDING'::character varying, 'PENDING_DELETION'::character varying, 'DELETED'::character varying, 'MOVE_FAILED'::character varying])::text[]))))"),
            new NamedObject("office_registration_status_history", "ck_reg_history_actor", "CHECK (((((changed_by_type)::text = 'OPERATOR'::text) AND (changed_by_operator_identifier IS NOT NULL)) OR (((changed_by_type)::text = 'SYSTEM'::text) AND (changed_by_operator_identifier IS NULL))))"),
            new NamedObject("property", "ck_property_soft_delete", "CHECK ((((deleted_at IS NULL) AND (deleted_by_member_id IS NULL)) OR ((deleted_at IS NOT NULL) AND (deleted_by_member_id IS NOT NULL))))"),
            new NamedObject("property", "fk_property_deleted_by_membership", "FOREIGN KEY (deleted_by_member_id, office_id) REFERENCES office_membership(member_id, office_id) ON DELETE RESTRICT"),
            new NamedObject("property_contract", "ck_property_contract_amounts", "CHECK (((((deal_type)::text = 'SALE'::text) AND (sale_price IS NOT NULL) AND (sale_price >= 0) AND (deposit_amount IS NULL) AND (monthly_rent_amount IS NULL)) OR (((deal_type)::text = 'JEONSE'::text) AND (sale_price IS NULL) AND (deposit_amount IS NOT NULL) AND (deposit_amount >= 0) AND (monthly_rent_amount IS NULL)) OR (((deal_type)::text = 'MONTHLY'::text) AND (sale_price IS NULL) AND (deposit_amount IS NOT NULL) AND (deposit_amount >= 0) AND (monthly_rent_amount IS NOT NULL) AND (monthly_rent_amount >= 0))))"),
            new NamedObject("property_contract", "ck_property_contract_period", "CHECK (((((deal_type)::text = 'SALE'::text) AND (contract_end_date IS NULL)) OR (((deal_type)::text = ANY ((ARRAY['JEONSE'::character varying, 'MONTHLY'::character varying])::text[])) AND (contract_end_date IS NOT NULL) AND (contract_end_date >= contract_start_date))))"),
            new NamedObject("property_contract", "ck_property_contract_previous_not_self", "CHECK (((previous_contract_id IS NULL) OR (previous_contract_id <> id)))"),
            new NamedObject("property_contract", "ck_property_contract_status_fields", "CHECK (((((status)::text = 'ACTIVE'::text) AND (ended_at IS NULL) AND (end_reason IS NULL)) OR (((status)::text = ANY ((ARRAY['COMPLETED'::character varying, 'RENEWED'::character varying])::text[])) AND (ended_at IS NOT NULL) AND (end_reason IS NULL)) OR (((status)::text = ANY ((ARRAY['TERMINATED'::character varying, 'CANCELLED'::character varying])::text[])) AND (ended_at IS NOT NULL) AND (end_reason IS NOT NULL))))"),
            new NamedObject("property_contract", "fk_property_contract_created_by_membership", "FOREIGN KEY (created_by_member_id, office_id) REFERENCES office_membership(member_id, office_id) ON DELETE RESTRICT"),
            new NamedObject("property_contract", "fk_property_contract_previous_same_property", "FOREIGN KEY (previous_contract_id, office_id, property_id) REFERENCES property_contract(id, office_id, property_id) ON DELETE RESTRICT"),
            new NamedObject("property_contract", "fk_property_contract_property_office_type", "FOREIGN KEY (property_id, office_id, deal_type) REFERENCES property(id, office_id, deal_type) ON DELETE RESTRICT"),
            new NamedObject("rate_limit_counter", "ck_rate_limit_counter_count", "CHECK ((request_count > 0))"),
            new NamedObject("terms_agreement", "fk_terms_agreement_catalog", "FOREIGN KEY (terms_code, terms_version) REFERENCES terms_catalog(terms_code, terms_version) ON DELETE RESTRICT"),
            new NamedObject("terms_catalog", "ck_terms_catalog_version_format", "CHECK (((terms_version)::text ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$'::text))")
    );

    // 참조 DDL에서 CREATE INDEX로 명시적으로 이름 붙인 인덱스 28개.
    // PK·UNIQUE 인라인 정의가 자동 생성하는 backing index는 이 목록에 포함하지 않는다.
    // 총 개수(pg_indexes 기준 60개)는 그 backing index까지 섞인 값이라 여기서는 쓰지 않는다.
    private static final List<NamedObject> NAMED_INDEXES = List.of(
            new NamedObject("coordination", "ix_coordination_office_confirmed", "CREATE INDEX ix_coordination_office_confirmed ON public.coordination USING btree (office_id, confirmed_at)"),
            new NamedObject("coordination", "ix_coordination_office_status_created", "CREATE INDEX ix_coordination_office_status_created ON public.coordination USING btree (office_id, status, created_at)"),
            new NamedObject("coordination", "uq_coordination_origin", "CREATE UNIQUE INDEX uq_coordination_origin ON public.coordination USING btree (origin_coordination_id) WHERE (origin_coordination_id IS NOT NULL)"),
            new NamedObject("coordination_candidate_time", "ix_candidate_time_coordination_date", "CREATE INDEX ix_candidate_time_coordination_date ON public.coordination_candidate_time USING btree (coordination_id, candidate_date, start_time)"),
            new NamedObject("coordination_status_history", "ix_status_history_coordination_created", "CREATE INDEX ix_status_history_coordination_created ON public.coordination_status_history USING btree (coordination_id, created_at)"),
            new NamedObject("document_move_outbox", "ix_document_move_outbox_due", "CREATE INDEX ix_document_move_outbox_due ON public.document_move_outbox USING btree (next_attempt_at) WHERE ((status)::text = 'PENDING'::text)"),
            new NamedObject("document_move_outbox", "ix_document_move_outbox_stuck", "CREATE INDEX ix_document_move_outbox_stuck ON public.document_move_outbox USING btree (lock_expires_at) WHERE ((status)::text = 'IN_PROGRESS'::text)"),
            new NamedObject("document_move_outbox", "uq_document_move_outbox_active", "CREATE UNIQUE INDEX uq_document_move_outbox_active ON public.document_move_outbox USING btree (office_registration_id, task_type) WHERE ((status)::text = ANY ((ARRAY['PENDING'::character varying, 'IN_PROGRESS'::character varying])::text[]))"),
            new NamedObject("email_verification_code", "uq_email_verification_active", "CREATE UNIQUE INDEX uq_email_verification_active ON public.email_verification_code USING btree (target_email, purpose) WHERE ((status)::text = 'ACTIVE'::text)"),
            new NamedObject("expiry_task", "ix_expiry_task_office_status_target", "CREATE INDEX ix_expiry_task_office_status_target ON public.expiry_task USING btree (office_id, status, target_date)"),
            new NamedObject("kakao_signup_ticket", "ix_kakao_signup_ticket_provider_key", "CREATE INDEX ix_kakao_signup_ticket_provider_key ON public.kakao_signup_ticket USING btree (provider_key)"),
            new NamedObject("login_history", "ix_login_history_member_created", "CREATE INDEX ix_login_history_member_created ON public.login_history USING btree (member_id, created_at DESC)"),
            new NamedObject("login_identity", "uq_login_identity_active", "CREATE UNIQUE INDEX uq_login_identity_active ON public.login_identity USING btree (provider, provider_key) WHERE (revoked_at IS NULL)"),
            new NamedObject("member", "uq_member_email_active", "CREATE UNIQUE INDEX uq_member_email_active ON public.member USING btree (email) WHERE ((account_status)::text <> 'WITHDRAWN'::text)"),
            new NamedObject("oauth_transaction", "ix_oauth_transaction_expires", "CREATE INDEX ix_oauth_transaction_expires ON public.oauth_transaction USING btree (expires_at) WHERE (used_at IS NULL)"),
            new NamedObject("office_membership", "ix_office_membership_member_status", "CREATE INDEX ix_office_membership_member_status ON public.office_membership USING btree (member_id, status)"),
            new NamedObject("office_membership", "uq_office_membership_member_approved", "CREATE UNIQUE INDEX uq_office_membership_member_approved ON public.office_membership USING btree (member_id) WHERE ((status)::text = 'APPROVED'::text)"),
            new NamedObject("office_registration", "ix_office_registration_bizno_status", "CREATE INDEX ix_office_registration_bizno_status ON public.office_registration USING btree (business_registration_number, status)"),
            new NamedObject("office_registration", "uq_office_registration_pending_applicant", "CREATE UNIQUE INDEX uq_office_registration_pending_applicant ON public.office_registration USING btree (applicant_member_id) WHERE ((status)::text = ANY ((ARRAY['PENDING_VERIFICATION'::character varying, 'MANUAL_REVIEW'::character varying])::text[]))"),
            new NamedObject("office_registration", "uq_office_registration_pending_bizno", "CREATE UNIQUE INDEX uq_office_registration_pending_bizno ON public.office_registration USING btree (business_registration_number) WHERE ((status)::text = ANY ((ARRAY['PENDING_VERIFICATION'::character varying, 'MANUAL_REVIEW'::character varying])::text[]))"),
            new NamedObject("office_registration", "uq_office_registration_result", "CREATE UNIQUE INDEX uq_office_registration_result ON public.office_registration USING btree (resulting_office_id) WHERE (resulting_office_id IS NOT NULL)"),
            new NamedObject("property", "ix_property_active", "CREATE INDEX ix_property_active ON public.property USING btree (office_id, property_status) WHERE (deleted_at IS NULL)"),
            new NamedObject("property_contract", "ix_property_contract_office_status_start", "CREATE INDEX ix_property_contract_office_status_start ON public.property_contract USING btree (office_id, status, contract_start_date DESC)"),
            new NamedObject("property_contract", "ix_property_contract_property_created", "CREATE INDEX ix_property_contract_property_created ON public.property_contract USING btree (property_id, created_at DESC)"),
            new NamedObject("property_contract", "uq_property_contract_active", "CREATE UNIQUE INDEX uq_property_contract_active ON public.property_contract USING btree (property_id) WHERE ((status)::text = 'ACTIVE'::text)"),
            new NamedObject("property_contract", "uq_property_contract_previous", "CREATE UNIQUE INDEX uq_property_contract_previous ON public.property_contract USING btree (previous_contract_id) WHERE (previous_contract_id IS NOT NULL)"),
            new NamedObject("rate_limit_counter", "ix_rate_limit_counter_window_started_at", "CREATE INDEX ix_rate_limit_counter_window_started_at ON public.rate_limit_counter USING btree (window_started_at)"),
            new NamedObject("terms_agreement", "ix_terms_agreement_member_code", "CREATE INDEX ix_terms_agreement_member_code ON public.terms_agreement USING btree (member_id, terms_code, recorded_at DESC, id DESC)")
    );

    @Test
    void 참조_DDL의_명시적_제약_46개가_이름과_정의까지_전부_일치한다() throws Exception {
        withRollback(conn -> {
            for (NamedObject c : NAMED_CONSTRAINTS) {
                assertConstraintMatches(conn, c);
            }
        });
    }

    @Test
    void 참조_DDL의_명시적_인덱스_28개가_이름과_정의까지_전부_일치한다() throws Exception {
        withRollback(conn -> {
            for (NamedObject i : NAMED_INDEXES) {
                assertIndexMatches(conn, i);
            }
        });
    }

    private void assertConstraintMatches(Connection conn, NamedObject c) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?")) {
            ps.setString(1, c.name());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("제약 %s(%s 테이블)가 존재해야 함", c.name(), c.table()).isTrue();
                assertThat(rs.getString(1)).as("제약 %s의 정의", c.name()).isEqualTo(c.definition());
            }
        }
    }

    private void assertIndexMatches(Connection conn, NamedObject i) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND indexname = ?")) {
            ps.setString(1, i.name());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("인덱스 %s(%s 테이블)가 존재해야 함", i.name(), i.table()).isTrue();
                assertThat(rs.getString(1)).as("인덱스 %s의 정의", i.name()).isEqualTo(i.definition());
            }
        }
    }

    private int countTables(Connection conn) throws java.sql.SQLException {
        return countIn(conn, "SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema='public' AND table_name = ANY(?)");
    }

    private int countIn(Connection conn, String sql) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setArray(1, conn.createArrayOf("text", DOMAIN_TABLES.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private record NamedObject(String table, String name, String definition) {
    }
}
