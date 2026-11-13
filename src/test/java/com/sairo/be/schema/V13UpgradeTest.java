package com.sairo.be.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class V13UpgradeTest {

  @Container
  private final PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

  private static final String MIGRATION_LOCATION = "filesystem:src/main/resources/db/migration";

  private Flyway flyway(String target) {
    return Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations(MIGRATION_LOCATION)
        .target(target)
        .load();
  }

  private Connection connect() throws Exception {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private long count(Connection conn, String sql) throws Exception {
    try (PreparedStatement ps = conn.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private String text(Connection conn, String sql) throws Exception {
    try (PreparedStatement ps = conn.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getString(1);
    }
  }

  private TreeSet<String> publicTables(Connection conn) throws Exception {
    TreeSet<String> tables = new TreeSet<>();
    try (PreparedStatement ps =
            conn.prepareStatement(
                "SELECT table_name FROM information_schema.tables"
                    + " WHERE table_schema = 'public' AND table_type = 'BASE TABLE'");
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        tables.add(rs.getString(1));
      }
    }
    tables.remove("flyway_schema_history");
    return tables;
  }

  private String constraintCountSql(String type) {
    return "SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace"
        + " WHERE n.nspname = 'public' AND c.contype = '"
        + type
        + "' AND c.conrelid <> 'flyway_schema_history'::regclass";
  }

  @Test
  void 빈_데이터베이스에서_V1부터_V13까지_순서대로_적용되고_validate를_통과한다() throws Exception {
    Flyway flyway = flyway("latest");
    assertThatCode(flyway::migrate).doesNotThrowAnyException();
    assertThatCode(flyway::validate).doesNotThrowAnyException();

    try (Connection conn = connect();
        PreparedStatement ps =
            conn.prepareStatement(
                "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank");
        ResultSet rs = ps.executeQuery()) {
      List<String> versions = new ArrayList<>();
      while (rs.next()) {
        assertThat(rs.getBoolean("success")).isTrue();
        versions.add(rs.getString("version"));
      }
      assertThat(versions)
          .startsWith("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13")
          .contains("16");
    }
  }

  @Test
  void V13_적용_후_표_컬럼_외래키_개수가_정본_DDL과_일치한다() throws Exception {
    flyway("latest").migrate();

    try (Connection conn = connect()) {
      TreeSet<String> tables = publicTables(conn);
      assertThat(tables).hasSize(141);
      assertThat(tables)
          .contains(
              "visit_request",
              "visit_approver_condition",
              "tax_document",
              "pricing_change_notice",
              "notification_delivery")
          .doesNotContain("coordination", "property_contract", "spring_session", "expiry_task");

      assertThat(
              count(
                  conn,
                  "SELECT count(*) FROM information_schema.columns c"
                      + " JOIN information_schema.tables t ON t.table_schema = c.table_schema"
                      + " AND t.table_name = c.table_name AND t.table_type = 'BASE TABLE'"
                      + " WHERE c.table_schema = 'public' AND c.table_name <> 'flyway_schema_history'"))
          .isEqualTo(1720);
      assertThat(count(conn, constraintCountSql("p"))).isEqualTo(141);
      assertThat(count(conn, constraintCountSql("f"))).isEqualTo(343);
    }
  }

  @Test
  void V13이_추가한_대표_제약과_인덱스가_카탈로그에_존재한다() throws Exception {
    flyway("latest").migrate();

    try (Connection conn = connect()) {
      for (String constraint :
          List.of(
              "ck_terms_agreement_consent_path",
              "ck_terms_agreement_office_scope",
              "ck_property_access_approval_anonymized",
              "ck_property_access_approval_phone",
              "visit_schedule_item_result_history_cancel_reason_code_check",
              "ex_trial_event_general_window",
              "ck_visit_request_cross_office",
              "ck_visit_approver_property_link_anonymized")) {
        assertThat(
                count(
                    conn,
                    "SELECT count(*) FROM pg_constraint WHERE conname = '" + constraint + "'"))
            .as(constraint)
            .isEqualTo(1);
      }
      for (String index :
          List.of(
              "uq_visit_request_active_candidate",
              "uq_visit_approver_condition_active",
              "uq_visit_approver_property_link_active",
              "uq_price_schedule_standard_effective",
              "ix_property_access_approval_phone_hash")) {
        assertThat(count(conn, "SELECT count(*) FROM pg_indexes WHERE indexname = '" + index + "'"))
            .as(index)
            .isEqualTo(1);
      }
    }
  }

  @Test
  void V16이_더한_표와_컬럼이_정본_타입으로_존재한다() throws Exception {
    flyway("latest").migrate();

    try (Connection conn = connect()) {
      for (String[] column :
          new String[][] {
            {"property_access_approval", "approver_phone_encrypted", "character varying", "YES"},
            {"visit_assistant_notice_log", "supervisor_additional_notice", "text", "YES"},
            {
              "visit_assistant_notice_log",
              "supervisor_additional_noticed_at",
              "timestamp with time zone",
              "YES"
            },
            {"ops_approval_request", "requested_expires_minutes", "integer", "YES"},
            {"legal_authority_request", "close_note", "text", "YES"},
            {"privacy_request", "version", "bigint", "NO"},
            {"deletion_queue", "verification_evidence", "text", "YES"},
            {"export_job", "archive_grant_id", "bigint", "YES"},
            {"office", "closed_at", "timestamp with time zone", "YES"},
            {"notification_delivery", "notification_id", "bigint", "NO"},
            {"notification_delivery", "channel", "character varying", "NO"},
            {"notification_delivery", "status", "character varying", "NO"},
            {"notification_delivery", "attempt_count", "integer", "NO"},
            {"notification_delivery", "next_attempt_at", "timestamp with time zone", "YES"},
            {"notification_delivery", "last_error_code", "character varying", "YES"}
          }) {
        assertThat(
                text(
                    conn,
                    "SELECT data_type || ' ' || is_nullable FROM information_schema.columns WHERE"
                        + " table_schema = 'public' AND table_name = '"
                        + column[0]
                        + "' AND column_name = '"
                        + column[1]
                        + "'"))
            .as(column[0] + "." + column[1])
            .isEqualTo(column[2] + " " + column[3]);
      }
      assertThat(
              count(
                  conn,
                  "SELECT character_maximum_length FROM information_schema.columns WHERE"
                      + " table_name = 'property_access_approval' AND column_name ="
                      + " 'approver_phone_encrypted'"))
          .isEqualTo(255);
      assertThat(
              count(
                  conn,
                  "SELECT count(*) FROM information_schema.columns WHERE table_name ="
                      + " 'property_access_approval' AND column_name = 'approver_phone'"))
          .isZero();
      assertThat(
              count(
                  conn,
                  "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public'"
                      + " AND table_name = 'notification_delivery'"))
          .isEqualTo(11);
      assertThat(
              text(
                  conn,
                  "SELECT column_default FROM information_schema.columns WHERE"
                      + " table_name = 'privacy_request' AND column_name = 'version'"))
          .isEqualTo("1");
    }
  }

  @Test
  void V16이_더하거나_바꾼_제약이_카탈로그에_새_정의로_존재한다() throws Exception {
    flyway("latest").migrate();

    try (Connection conn = connect()) {
      for (String constraint :
          List.of(
              "ck_export_job_scope_archive",
              "fk_export_job_archive_grant",
              "ck_visit_assistant_notice_log_additional_notice",
              "ck_ops_approval_request_requested_expires_minutes",
              "privacy_request_version_check",
              "ck_notification_delivery_delivered",
              "ck_notification_delivery_pending",
              "notification_delivery_notification_id_channel_key")) {
        assertThat(
                count(
                    conn,
                    "SELECT count(*) FROM pg_constraint WHERE conname = '" + constraint + "'"))
            .as(constraint)
            .isEqualTo(1);
      }
      for (String[] changed :
          new String[][] {
            {"notification_notification_type_check", "TERMS_CHANGED"},
            {"notification_target_type_check", "CONTRACT"},
            {"ops_raw_access_grant_check", "expires_at > executed_at"},
            {"export_job_scope_check", "ARCHIVE"},
            {"ck_export_job_scope_tables", "ARCHIVE"},
            {"visit_coordination_reschedule_trigger_type_check", "ASSIGNEE_HANDOVER"},
            {"visit_request_revalidation_failure_code_check", "DEPUTY_VISIT_LOCKED"}
          }) {
        assertThat(
                text(
                    conn,
                    "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = '"
                        + changed[0]
                        + "'"))
            .as(changed[0])
            .contains(changed[1]);
      }
    }
  }

  @Test
  void V12_스키마의_기존_행은_V13_적용_후_백필되고_새_NOT_NULL_컬럼이_채워진다() throws Exception {
    flyway("12").migrate();
    try (Connection conn = connect()) {
      assertThat(publicTables(conn)).hasSize(108);
      seedV12Rows(conn);
    }

    flyway("latest").migrate();

    try (Connection conn = connect()) {
      assertThat(publicTables(conn)).hasSize(141);

      assertThat(count(conn, "SELECT count(*) FROM terms_agreement")).isEqualTo(1);
      assertThat(text(conn, "SELECT consent_path FROM terms_agreement")).isEqualTo("UNKNOWN");
      assertThat(text(conn, "SELECT office_id::text FROM terms_agreement")).isNull();

      assertThat(text(conn, "SELECT approval_role FROM property_access_approval"))
          .isEqualTo("PRIMARY");
      assertThat(text(conn, "SELECT retention_until::text FROM property_access_approval"))
          .isEqualTo(
              text(
                  conn,
                  "SELECT ((valid_until AT TIME ZONE 'Asia/Seoul')::date + 90)::text"
                      + " FROM property_access_approval"));
      assertThat(text(conn, "SELECT anonymized_at::text FROM property_access_approval")).isNull();
      assertThat(text(conn, "SELECT approver_phone_hash FROM property_access_approval")).isNull();
      assertThat(text(conn, "SELECT approver_phone_encrypted FROM property_access_approval"))
          .isEqualTo("010-1111-2222");

      assertThat(text(conn, "SELECT property_office_id::text FROM visit_candidate_property"))
          .isEqualTo("1");
      assertThat(text(conn, "SELECT retention_until::text FROM visit_customer_link"))
          .isEqualTo(
              text(
                  conn,
                  "SELECT ((expires_at AT TIME ZONE 'Asia/Seoul')::date + 90)::text"
                      + " FROM visit_customer_link"));
    }
  }

  private void seedV12Rows(Connection conn) throws Exception {
    try (Statement st = conn.createStatement()) {
      st.execute(
          "INSERT INTO app_user (kakao_provider_key, name) VALUES ('kakao-v13-seed', '사용자')");
      st.execute(
          "INSERT INTO office (name, entity_type, registration_authority,"
              + " real_estate_license_number, business_registration_number, representative_name,"
              + " representative_phone, address)"
              + " VALUES ('사무소', 'INDIVIDUAL', '관청', 'LIC-V13', '1234567890', '대표',"
              + " '010-0000-0000', '서울시')");
      st.execute(
          "INSERT INTO office_membership (office_id, user_id, role, status, approved_at)"
              + " VALUES (1, 1, 'OWNER', 'APPROVED', now())");
      st.execute(
          "INSERT INTO property_category (office_id, code, name, is_system)"
              + " VALUES (NULL, 'APT', '아파트', true)");
      st.execute("INSERT INTO property (office_id, category_id, name) VALUES (1, 1, '매물')");
      st.execute(
          "INSERT INTO property_access_approval (property_id, approver_type, approver_name,"
              + " approver_phone, valid_from, valid_until, source)"
              + " VALUES (1, 'OWNER', '소유자', '010-1111-2222',"
              + " now() - interval '10 days', now() - interval '1 day', 'DIRECT')");
      st.execute(
          "INSERT INTO terms_catalog (terms_code, terms_version, title, category, required,"
              + " document_url, content_hash, effective_at)"
              + " VALUES ('PRIVACY', 'v1', '약관', 'TERMS', true, 'https://example.com/t',"
              + " repeat('a', 64), now())");
      st.execute(
          "INSERT INTO terms_agreement (user_id, terms_code, terms_version, agreed)"
              + " VALUES (1, 'PRIVACY', 'v1', true)");

      st.execute("SET session_replication_role = replica");
      st.execute(
          "INSERT INTO visit_coordination (office_id, customer_id, manager_membership_id, status,"
              + " period_start_date, period_end_date)"
              + " VALUES (1, 1, 1, 'TIME_COLLECTING', current_date, current_date + 7)");
      st.execute(
          "INSERT INTO visit_candidate_property (coordination_id, office_id, property_id,"
              + " sort_order) VALUES (1, 1, 1, 1)");
      st.execute(
          "INSERT INTO visit_customer_link (coordination_id, token_hash, expires_at)"
              + " VALUES (1, repeat('b', 64), now() + interval '3 days')");
      st.execute("SET session_replication_role = DEFAULT");
    }
  }
}
