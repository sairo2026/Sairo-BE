package com.sairo.be.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SchemaMigrationTest extends AbstractSchemaTest {

  private static final List<String> DOMAIN_TABLES =
      List.of(
          "app_user",
          "login_history",
          "terms_catalog",
          "terms_agreement",
          "office",
          "office_registration",
          "office_registration_status_history",
          "office_membership",
          "office_membership_status_history",
          "property",
          "property_contract",
          "coordination",
          "coordination_candidate_time",
          "coordination_customer_response",
          "customer_response_candidate",
          "customer_response_link",
          "coordination_status_history",
          "expiry_task",
          "operator_account",
          "rate_limit_counter",
          "shedlock");

  private static final Set<String> NON_DOMAIN_TABLES =
      Set.of("spring_session", "spring_session_attributes", "flyway_schema_history");

  @Autowired private Flyway flyway;

  @Test
  void flyway_validate_통과한다() {
    flyway.validate();
  }

  @Test
  void V1부터_V10까지_열_마이그레이션이_모두_성공했다() throws Exception {
    withRollback(
        conn -> {
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")) {
            try (ResultSet rs = ps.executeQuery()) {
              List<String> versions = new java.util.ArrayList<>();
              while (rs.next()) {
                assertThat(rs.getBoolean("success"))
                    .as("version %s는 성공해야 함", rs.getString("version"))
                    .isTrue();
                versions.add(rs.getString("version"));
              }
              assertThat(versions)
                  .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
            }
          }
        });
  }

  @Test
  void 도메인_테이블_집합이_정확히_21개와_일치한다() throws Exception {
    withRollback(
        conn -> {
          Set<String> actual = new HashSet<>();
          try (PreparedStatement ps =
                  conn.prepareStatement(
                      "SELECT table_name FROM information_schema.tables WHERE table_schema='public'");
              ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
              String name = rs.getString(1);
              if (!NON_DOMAIN_TABLES.contains(name)) {
                actual.add(name);
              }
            }
          }
          assertThat(actual)
              .as("public 스키마의 도메인 테이블 집합이 DOMAIN_TABLES와 정확히 같아야 함(추가/누락 없이)")
              .containsExactlyInAnyOrderElementsOf(DOMAIN_TABLES);
        });
  }

  @Test
  void 회원가입_사업자인증_상태_테이블은_V10에서_제거됐다() throws Exception {
    withRollback(
        conn -> {
          for (String removed :
              List.of(
                  "member",
                  "login_identity",
                  "email_verification_code",
                  "oauth_transaction",
                  "kakao_signup_ticket",
                  "document_move_outbox",
                  "operator")) {
            assertThat(tableExists(conn, removed)).as("%s는 V10에서 제거되어야 함", removed).isZero();
          }
        });
  }

  @Test
  void 컬럼_165개가_이름_타입_길이_NULL여부_기본값까지_전부_일치한다() throws Exception {
    withRollback(
        conn -> {
          for (ColumnSpec c : COLUMN_SPECS) {
            assertColumnMatches(conn, c);
          }
        });
  }

  private static final List<NamedObject> NAMED_CONSTRAINTS =
      List.of(
          new NamedObject(
              "app_user",
              "ck_app_user_withdrawn",
              "CHECK ((((account_status)::text = 'WITHDRAWN'::text) = (withdrawn_at IS NOT NULL)))"),
          new NamedObject(
              "office_registration",
              "ck_office_registration_status",
              "CHECK (((((status)::text = ANY ((ARRAY['PENDING_REVIEW'::character varying, 'EVIDENCE_REQUESTED'::character varying])::text[])) AND (resulting_office_id IS NULL) AND (rejection_reason IS NULL) AND (reviewed_at IS NULL) AND (cancelled_at IS NULL) AND (resolved_at IS NULL)) OR (((status)::text = 'APPROVED'::text) AND (resulting_office_id IS NOT NULL) AND (rejection_reason IS NULL) AND (reviewed_at IS NOT NULL) AND (cancelled_at IS NULL) AND (resolved_at IS NULL)) OR (((status)::text = 'REJECTED'::text) AND (resulting_office_id IS NULL) AND (rejection_reason IS NOT NULL) AND (reviewed_at IS NOT NULL) AND (cancelled_at IS NULL)) OR (((status)::text = 'CANCELLED'::text) AND (resulting_office_id IS NULL) AND (rejection_reason IS NULL) AND (reviewed_at IS NULL) AND (cancelled_at IS NOT NULL) AND (resolved_at IS NULL))))"),
          new NamedObject(
              "office_registration_status_history",
              "ck_registration_history_actor",
              "CHECK ((((changed_by_type)::text = 'OPERATOR'::text) = (changed_by_operator_id IS NOT NULL)))"),
          new NamedObject(
              "office_membership",
              "ck_office_membership_status",
              "CHECK (((((status)::text = 'PENDING'::text) AND ((role)::text = 'STAFF'::text) AND (reviewed_at IS NULL) AND (rejection_reason IS NULL) AND (revoked_at IS NULL) AND (revocation_reason IS NULL) AND (cancelled_at IS NULL) AND (resolved_at IS NULL)) OR (((status)::text = 'APPROVED'::text) AND (reviewed_at IS NOT NULL) AND (rejection_reason IS NULL) AND (revoked_at IS NULL) AND (revocation_reason IS NULL) AND (cancelled_at IS NULL) AND (resolved_at IS NULL)) OR (((status)::text = 'REJECTED'::text) AND (reviewed_at IS NOT NULL) AND (rejection_reason IS NOT NULL) AND (revoked_at IS NULL) AND (revocation_reason IS NULL) AND (cancelled_at IS NULL)) OR (((status)::text = 'REVOKED'::text) AND (reviewed_at IS NOT NULL) AND (rejection_reason IS NULL) AND (revoked_at IS NOT NULL) AND (revocation_reason IS NOT NULL) AND (cancelled_at IS NULL) AND (resolved_at IS NULL)) OR (((status)::text = 'CANCELLED'::text) AND (reviewed_at IS NULL) AND (rejection_reason IS NULL) AND (revoked_at IS NULL) AND (revocation_reason IS NULL) AND (cancelled_at IS NOT NULL) AND (resolved_at IS NULL))))"),
          new NamedObject(
              "office_membership_status_history",
              "ck_membership_history_actor",
              "CHECK (((((changed_by_type)::text = 'ADMIN'::text) AND (changed_by_membership_id IS NOT NULL) AND (changed_by_membership_id <> membership_id)) OR (((changed_by_type)::text = ANY ((ARRAY['APPLICANT'::character varying, 'SYSTEM'::character varying])::text[])) AND (changed_by_membership_id IS NULL))))"),
          new NamedObject(
              "property_contract",
              "ck_property_contract_sale_terminal",
              "CHECK ((((deal_type)::text <> 'SALE'::text) OR ((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[]))))"),
          new NamedObject(
              "coordination",
              "ck_coordination_completed",
              "CHECK ((((status)::text <> 'COMPLETED'::text) OR ((scheduled_at IS NOT NULL) AND (confirmed_at IS NOT NULL))))"),
          new NamedObject(
              "coordination",
              "ck_coordination_cancelled",
              "CHECK ((((status)::text = 'CANCELLED'::text) = (cancelled_at IS NOT NULL)))"),
          new NamedObject(
              "coordination",
              "fk_selected_buyer",
              "FOREIGN KEY (selected_buyer_response_id, id) REFERENCES coordination_customer_response(id, coordination_id)"),
          new NamedObject(
              "coordination",
              "fk_confirmed_candidate",
              "FOREIGN KEY (confirmed_candidate_time_id, id) REFERENCES coordination_candidate_time(id, coordination_id)"),
          new NamedObject(
              "office_registration_status_history",
              "fk_registration_history_operator",
              "FOREIGN KEY (changed_by_operator_id) REFERENCES operator_account(id)"));

  private static final List<NamedObject> NAMED_INDEXES =
      List.of(
          new NamedObject(
              "login_history",
              "ix_login_history_user_created",
              "CREATE INDEX ix_login_history_user_created ON public.login_history USING btree (user_id, created_at DESC)"),
          new NamedObject(
              "office_registration",
              "uq_office_registration_pending_applicant",
              "CREATE UNIQUE INDEX uq_office_registration_pending_applicant ON public.office_registration USING btree (applicant_user_id) WHERE ((status)::text = ANY ((ARRAY['PENDING_REVIEW'::character varying, 'EVIDENCE_REQUESTED'::character varying])::text[]))"),
          new NamedObject(
              "office_registration",
              "uq_office_registration_pending_bizno",
              "CREATE UNIQUE INDEX uq_office_registration_pending_bizno ON public.office_registration USING btree (business_registration_number) WHERE ((status)::text = ANY ((ARRAY['PENDING_REVIEW'::character varying, 'EVIDENCE_REQUESTED'::character varying])::text[]))"),
          new NamedObject(
              "office_membership",
              "uq_user_approved_office",
              "CREATE UNIQUE INDEX uq_user_approved_office ON public.office_membership USING btree (user_id) WHERE ((status)::text = 'APPROVED'::text)"),
          new NamedObject(
              "office_membership",
              "uq_user_pending_office",
              "CREATE UNIQUE INDEX uq_user_pending_office ON public.office_membership USING btree (user_id) WHERE ((status)::text = 'PENDING'::text)"),
          new NamedObject(
              "property_contract",
              "uq_property_active_contract",
              "CREATE UNIQUE INDEX uq_property_active_contract ON public.property_contract USING btree (property_id) WHERE ((status)::text = 'ACTIVE'::text)"),
          new NamedObject(
              "coordination_customer_response",
              "uq_coordination_tenant",
              "CREATE UNIQUE INDEX uq_coordination_tenant ON public.coordination_customer_response USING btree (coordination_id) WHERE ((role)::text = 'TENANT'::text)"),
          new NamedObject(
              "customer_response_link",
              "uq_response_active_link",
              "CREATE UNIQUE INDEX uq_response_active_link ON public.customer_response_link USING btree (response_id) WHERE (revoked_at IS NULL)"),
          new NamedObject(
              "coordination",
              "ix_coordination_office_status",
              "CREATE INDEX ix_coordination_office_status ON public.coordination USING btree (office_id, status, created_at DESC)"),
          new NamedObject(
              "coordination_customer_response",
              "ix_response_coordination",
              "CREATE INDEX ix_response_coordination ON public.coordination_customer_response USING btree (coordination_id, role)"),
          new NamedObject(
              "customer_response_link",
              "ix_link_hash",
              "CREATE INDEX ix_link_hash ON public.customer_response_link USING btree (token_hash)"));

  private static final List<ColumnSpec> COLUMN_SPECS =
      List.of(
          new ColumnSpec(
              "app_user",
              "account_status",
              "character varying",
              10,
              false,
              "'ACTIVE'::character varying"),
          new ColumnSpec(
              "app_user", "created_at", "timestamp with time zone", null, false, "now()"),
          new ColumnSpec("app_user", "id", "bigint", null, false, null),
          new ColumnSpec("app_user", "kakao_provider_key", "character varying", 255, false, null),
          new ColumnSpec("app_user", "name", "character varying", 50, false, null),
          new ColumnSpec("app_user", "withdrawn_at", "timestamp with time zone", null, true, null),
          new ColumnSpec("coordination", "cancel_reason", "text", null, true, null),
          new ColumnSpec(
              "coordination", "cancelled_at", "timestamp with time zone", null, true, null),
          new ColumnSpec(
              "coordination", "confirmed_at", "timestamp with time zone", null, true, null),
          new ColumnSpec("coordination", "confirmed_candidate_time_id", "bigint", null, true, null),
          new ColumnSpec(
              "coordination", "created_at", "timestamp with time zone", null, false, "now()"),
          new ColumnSpec("coordination", "created_by_membership_id", "bigint", null, false, null),
          new ColumnSpec("coordination", "id", "bigint", null, false, null),
          new ColumnSpec("coordination", "office_id", "bigint", null, false, null),
          new ColumnSpec("coordination", "property_id", "bigint", null, false, null),
          new ColumnSpec(
              "coordination", "scheduled_at", "timestamp with time zone", null, true, null),
          new ColumnSpec("coordination", "selected_buyer_response_id", "bigint", null, true, null),
          new ColumnSpec("coordination", "status", "character varying", 40, false, null),
          new ColumnSpec(
              "coordination", "updated_at", "timestamp with time zone", null, false, "now()"),
          new ColumnSpec(
              "coordination_candidate_time", "coordination_id", "bigint", null, false, null),
          new ColumnSpec("coordination_candidate_time", "id", "bigint", null, false, null),
          new ColumnSpec(
              "coordination_candidate_time",
              "starts_at",
              "timestamp with time zone",
              null,
              false,
              null),
          new ColumnSpec(
              "coordination_customer_response", "coordination_id", "bigint", null, false, null),
          new ColumnSpec(
              "coordination_customer_response",
              "created_at",
              "timestamp with time zone",
              null,
              false,
              "now()"),
          new ColumnSpec(
              "coordination_customer_response",
              "customer_name",
              "character varying",
              50,
              false,
              null),
          new ColumnSpec(
              "coordination_customer_response",
              "customer_phone",
              "character varying",
              30,
              false,
              null),
          new ColumnSpec("coordination_customer_response", "id", "bigint", null, false, null),
          new ColumnSpec(
              "coordination_customer_response", "reset_count", "integer", null, false, "0"),
          new ColumnSpec(
              "coordination_customer_response", "result", "character varying", 30, false, null),
          new ColumnSpec(
              "coordination_customer_response", "role", "character varying", 10, false, null),
          new ColumnSpec(
              "coordination_customer_response",
              "submitted_at",
              "timestamp with time zone",
              null,
              true,
              null),
          new ColumnSpec(
              "coordination_status_history", "actor_membership_id", "bigint", null, true, null),
          new ColumnSpec(
              "coordination_status_history", "coordination_id", "bigint", null, false, null),
          new ColumnSpec(
              "coordination_status_history",
              "created_at",
              "timestamp with time zone",
              null,
              false,
              "now()"),
          new ColumnSpec(
              "coordination_status_history", "from_status", "character varying", 40, true, null),
          new ColumnSpec("coordination_status_history", "id", "bigint", null, false, null),
          new ColumnSpec("coordination_status_history", "reason", "text", null, true, null),
          new ColumnSpec(
              "coordination_status_history", "source", "character varying", 20, false, null),
          new ColumnSpec(
              "coordination_status_history", "to_status", "character varying", 40, false, null),
          new ColumnSpec(
              "customer_response_candidate", "candidate_time_id", "bigint", null, false, null),
          new ColumnSpec(
              "customer_response_candidate", "coordination_id", "bigint", null, false, null),
          new ColumnSpec(
              "customer_response_candidate", "is_selected", "boolean", null, false, "false"),
          new ColumnSpec("customer_response_candidate", "response_id", "bigint", null, false, null),
          new ColumnSpec(
              "customer_response_link",
              "expires_at",
              "timestamp with time zone",
              null,
              false,
              null),
          new ColumnSpec("customer_response_link", "id", "bigint", null, false, null),
          new ColumnSpec(
              "customer_response_link",
              "issued_at",
              "timestamp with time zone",
              null,
              false,
              "now()"),
          new ColumnSpec("customer_response_link", "response_id", "bigint", null, false, null),
          new ColumnSpec(
              "customer_response_link", "revoked_at", "timestamp with time zone", null, true, null),
          new ColumnSpec("customer_response_link", "token_hash", "character", 64, false, null),
          new ColumnSpec("expiry_task", "cancel_reason", "character varying", 40, true, null),
          new ColumnSpec(
              "expiry_task", "cancelled_at", "timestamp with time zone", null, true, null),
          new ColumnSpec("expiry_task", "contract_id", "bigint", null, false, null),
          new ColumnSpec(
              "expiry_task", "created_at", "timestamp with time zone", null, false, "now()"),
          new ColumnSpec("expiry_task", "id", "bigint", null, false, null),
          new ColumnSpec("expiry_task", "office_id", "bigint", null, false, null),
          new ColumnSpec("expiry_task", "property_id", "bigint", null, false, null),
          new ColumnSpec("expiry_task", "status", "character varying", 10, false, null),
          new ColumnSpec("expiry_task", "target_date", "date", null, false, null),
          new ColumnSpec(
              "login_history", "created_at", "timestamp with time zone", null, false, "now()"),
          new ColumnSpec("login_history", "id", "bigint", null, false, null),
          new ColumnSpec("login_history", "ip_address", "inet", null, true, null),
          new ColumnSpec("login_history", "user_agent", "character varying", 500, true, null),
          new ColumnSpec("login_history", "user_id", "bigint", null, false, null),
          new ColumnSpec("office", "address", "character varying", 300, false, null),
          new ColumnSpec(
              "office", "business_registration_number", "character varying", 10, false, null),
          new ColumnSpec("office", "created_at", "timestamp with time zone", null, false, "now()"),
          new ColumnSpec("office", "id", "bigint", null, false, null),
          new ColumnSpec("office", "name", "character varying", 100, false, null),
          new ColumnSpec("office", "phone", "character varying", 30, false, null),
          new ColumnSpec(
              "office", "real_estate_license_number", "character varying", 30, false, null),
          new ColumnSpec("office", "representative_name", "character varying", 50, false, null),
          new ColumnSpec(
              "office_membership", "cancelled_at", "timestamp with time zone", null, true, null),
          new ColumnSpec("office_membership", "id", "bigint", null, false, null),
          new ColumnSpec("office_membership", "office_id", "bigint", null, false, null),
          new ColumnSpec("office_membership", "rejection_reason", "text", null, true, null),
          new ColumnSpec(
              "office_membership", "resolved_at", "timestamp with time zone", null, true, null),
          new ColumnSpec(
              "office_membership", "reviewed_at", "timestamp with time zone", null, true, null),
          new ColumnSpec("office_membership", "revocation_reason", "text", null, true, null),
          new ColumnSpec(
              "office_membership", "revoked_at", "timestamp with time zone", null, true, null),
          new ColumnSpec("office_membership", "role", "character varying", 10, false, null),
          new ColumnSpec("office_membership", "status", "character varying", 10, false, null),
          new ColumnSpec("office_membership", "user_id", "bigint", null, false, null),
          new ColumnSpec(
              "office_membership_status_history",
              "changed_by_membership_id",
              "bigint",
              null,
              true,
              null),
          new ColumnSpec(
              "office_membership_status_history",
              "changed_by_type",
              "character varying",
              10,
              false,
              null),
          new ColumnSpec(
              "office_membership_status_history",
              "created_at",
              "timestamp with time zone",
              null,
              false,
              "now()"),
          new ColumnSpec(
              "office_membership_status_history",
              "from_status",
              "character varying",
              20,
              true,
              null),
          new ColumnSpec("office_membership_status_history", "id", "bigint", null, false, null),
          new ColumnSpec(
              "office_membership_status_history", "membership_id", "bigint", null, false, null),
          new ColumnSpec("office_membership_status_history", "reason", "text", null, true, null),
          new ColumnSpec(
              "office_membership_status_history",
              "to_status",
              "character varying",
              20,
              false,
              null),
          new ColumnSpec("office_registration", "applicant_user_id", "bigint", null, false, null),
          new ColumnSpec(
              "office_registration",
              "business_registration_number",
              "character varying",
              10,
              false,
              null),
          new ColumnSpec(
              "office_registration", "cancelled_at", "timestamp with time zone", null, true, null),
          new ColumnSpec(
              "office_registration",
              "created_at",
              "timestamp with time zone",
              null,
              false,
              "now()"),
          new ColumnSpec(
              "office_registration", "evidence_object_key", "character varying", 500, true, null),
          new ColumnSpec("office_registration", "id", "bigint", null, false, null),
          new ColumnSpec(
              "office_registration",
              "real_estate_license_number",
              "character varying",
              30,
              false,
              null),
          new ColumnSpec("office_registration", "rejection_reason", "text", null, true, null),
          new ColumnSpec(
              "office_registration", "requested_address", "character varying", 300, false, null),
          new ColumnSpec(
              "office_registration",
              "requested_office_name",
              "character varying",
              100,
              false,
              null),
          new ColumnSpec(
              "office_registration", "requested_phone", "character varying", 30, false, null),
          new ColumnSpec(
              "office_registration",
              "requested_representative_name",
              "character varying",
              50,
              false,
              null),
          new ColumnSpec(
              "office_registration", "resolved_at", "timestamp with time zone", null, true, null),
          new ColumnSpec("office_registration", "resulting_office_id", "bigint", null, true, null),
          new ColumnSpec(
              "office_registration", "reviewed_at", "timestamp with time zone", null, true, null),
          new ColumnSpec("office_registration", "status", "character varying", 30, false, null),
          new ColumnSpec(
              "office_registration_status_history",
              "changed_by_operator_id",
              "bigint",
              null,
              true,
              null),
          new ColumnSpec(
              "office_registration_status_history",
              "changed_by_type",
              "character varying",
              10,
              false,
              null),
          new ColumnSpec(
              "office_registration_status_history",
              "created_at",
              "timestamp with time zone",
              null,
              false,
              "now()"),
          new ColumnSpec(
              "office_registration_status_history",
              "from_status",
              "character varying",
              30,
              true,
              null),
          new ColumnSpec("office_registration_status_history", "id", "bigint", null, false, null),
          new ColumnSpec("office_registration_status_history", "reason", "text", null, true, null),
          new ColumnSpec(
              "office_registration_status_history", "registration_id", "bigint", null, false, null),
          new ColumnSpec(
              "office_registration_status_history",
              "to_status",
              "character varying",
              30,
              false,
              null),
          new ColumnSpec(
              "operator_account", "created_at", "timestamp with time zone", null, false, "now()"),
          new ColumnSpec("operator_account", "id", "bigint", null, false, null),
          new ColumnSpec(
              "operator_account", "kakao_provider_key", "character varying", 255, false, null),
          new ColumnSpec("operator_account", "name", "character varying", 50, false, null),
          new ColumnSpec("operator_account", "status", "character varying", 10, false, null),
          new ColumnSpec("property", "address", "character varying", 300, false, null),
          new ColumnSpec("property", "address_detail", "character varying", 100, true, null),
          new ColumnSpec("property", "archived_at", "timestamp with time zone", null, true, null),
          new ColumnSpec(
              "property", "created_at", "timestamp with time zone", null, false, "now()"),
          new ColumnSpec("property", "deal_type", "character varying", 10, false, null),
          new ColumnSpec("property", "deleted_at", "timestamp with time zone", null, true, null),
          new ColumnSpec("property", "id", "bigint", null, false, null),
          new ColumnSpec("property", "office_id", "bigint", null, false, null),
          new ColumnSpec("property", "property_name", "character varying", 100, true, null),
          new ColumnSpec(
              "property",
              "property_status",
              "character varying",
              20,
              false,
              "'NO_CONTRACT'::character varying"),
          new ColumnSpec("property_contract", "contract_end_date", "date", null, true, null),
          new ColumnSpec("property_contract", "contract_start_date", "date", null, true, null),
          new ColumnSpec(
              "property_contract", "counterparty_name", "character varying", 50, true, null),
          new ColumnSpec(
              "property_contract", "counterparty_phone", "character varying", 30, true, null),
          new ColumnSpec(
              "property_contract", "created_at", "timestamp with time zone", null, false, "now()"),
          new ColumnSpec("property_contract", "deal_type", "character varying", 10, false, null),
          new ColumnSpec("property_contract", "deposit_amount", "bigint", null, true, null),
          new ColumnSpec("property_contract", "end_reason", "text", null, true, null),
          new ColumnSpec(
              "property_contract", "ended_at", "timestamp with time zone", null, true, null),
          new ColumnSpec("property_contract", "id", "bigint", null, false, null),
          new ColumnSpec("property_contract", "monthly_rent_amount", "bigint", null, true, null),
          new ColumnSpec("property_contract", "office_id", "bigint", null, false, null),
          new ColumnSpec(
              "property_contract", "owner_party_name", "character varying", 50, true, null),
          new ColumnSpec(
              "property_contract", "owner_party_phone", "character varying", 30, true, null),
          new ColumnSpec("property_contract", "previous_contract_id", "bigint", null, true, null),
          new ColumnSpec("property_contract", "property_id", "bigint", null, false, null),
          new ColumnSpec("property_contract", "sale_price", "bigint", null, true, null),
          new ColumnSpec("property_contract", "status", "character varying", 15, false, null),
          new ColumnSpec("rate_limit_counter", "bucket_key", "text", null, false, null),
          new ColumnSpec("rate_limit_counter", "request_count", "integer", null, false, null),
          new ColumnSpec(
              "rate_limit_counter",
              "window_started_at",
              "timestamp with time zone",
              null,
              false,
              null),
          new ColumnSpec(
              "shedlock", "lock_until", "timestamp without time zone", null, false, null),
          new ColumnSpec("shedlock", "locked_at", "timestamp without time zone", null, false, null),
          new ColumnSpec("shedlock", "locked_by", "character varying", 255, false, null),
          new ColumnSpec("shedlock", "name", "character varying", 64, false, null),
          new ColumnSpec("terms_agreement", "agreed", "boolean", null, false, null),
          new ColumnSpec("terms_agreement", "id", "bigint", null, false, null),
          new ColumnSpec(
              "terms_agreement", "recorded_at", "timestamp with time zone", null, false, "now()"),
          new ColumnSpec("terms_agreement", "terms_code", "character varying", 30, false, null),
          new ColumnSpec("terms_agreement", "terms_version", "character varying", 20, false, null),
          new ColumnSpec("terms_agreement", "user_id", "bigint", null, false, null),
          new ColumnSpec("terms_catalog", "content_hash", "character", 64, false, null),
          new ColumnSpec(
              "terms_catalog", "effective_at", "timestamp with time zone", null, false, null),
          new ColumnSpec("terms_catalog", "required", "boolean", null, false, null),
          new ColumnSpec("terms_catalog", "terms_code", "character varying", 30, false, null),
          new ColumnSpec("terms_catalog", "terms_version", "character varying", 20, false, null));

  @Test
  void 명시적_제약_11개가_이름과_정의까지_전부_일치한다() throws Exception {
    withRollback(
        conn -> {
          for (NamedObject c : NAMED_CONSTRAINTS) {
            assertConstraintMatches(conn, c);
          }
        });
  }

  @Test
  void 명시적_인덱스_11개가_이름과_정의까지_전부_일치한다() throws Exception {
    withRollback(
        conn -> {
          for (NamedObject i : NAMED_INDEXES) {
            assertIndexMatches(conn, i);
          }
        });
  }

  private void assertConstraintMatches(Connection conn, NamedObject c)
      throws java.sql.SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "SELECT pg_get_constraintdef(pgc.oid) FROM pg_constraint pgc "
                + "JOIN pg_class cls ON cls.oid = pgc.conrelid "
                + "JOIN pg_namespace ns ON ns.oid = cls.relnamespace "
                + "WHERE ns.nspname = 'public' AND cls.relname = ? AND pgc.conname = ?")) {
      ps.setString(1, c.table());
      ps.setString(2, c.name());
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("제약 %s(%s 테이블)가 존재해야 함", c.name(), c.table()).isTrue();
        assertThat(rs.getString(1)).as("제약 %s의 정의", c.name()).isEqualTo(c.definition());
      }
    }
  }

  private void assertIndexMatches(Connection conn, NamedObject i) throws java.sql.SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND tablename = ? AND indexname = ?")) {
      ps.setString(1, i.table());
      ps.setString(2, i.name());
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("인덱스 %s(%s 테이블)가 존재해야 함", i.name(), i.table()).isTrue();
        assertThat(rs.getString(1)).as("인덱스 %s의 정의", i.name()).isEqualTo(i.definition());
      }
    }
  }

  private void assertColumnMatches(Connection conn, ColumnSpec c) throws java.sql.SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "SELECT data_type, character_maximum_length, is_nullable, column_default "
                + "FROM information_schema.columns WHERE table_schema='public' AND table_name = ? AND column_name = ?")) {
      ps.setString(1, c.table());
      ps.setString(2, c.column());
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("컬럼 %s.%s가 존재해야 함", c.table(), c.column()).isTrue();
        assertThat(rs.getString("data_type"))
            .as("%s.%s 타입", c.table(), c.column())
            .isEqualTo(c.dataType());
        Integer actualLength = (Integer) rs.getObject("character_maximum_length");
        assertThat(actualLength).as("%s.%s 길이", c.table(), c.column()).isEqualTo(c.maxLength());
        boolean actualNullable = "YES".equals(rs.getString("is_nullable"));
        assertThat(actualNullable)
            .as("%s.%s NULL 허용 여부", c.table(), c.column())
            .isEqualTo(c.nullable());
        assertThat(rs.getString("column_default"))
            .as("%s.%s 기본값", c.table(), c.column())
            .isEqualTo(c.defaultExpr());
      }
    }
  }

  private int tableExists(Connection conn, String tableName) throws java.sql.SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name = ?")) {
      ps.setString(1, tableName);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private record NamedObject(String table, String name, String definition) {}

  private record ColumnSpec(
      String table,
      String column,
      String dataType,
      Integer maxLength,
      boolean nullable,
      String defaultExpr) {}
}
