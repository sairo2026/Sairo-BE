package com.sairo.be.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class V12UpgradeTest {

  @Container
  private final PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

  private static final Path SOURCE = Path.of("src/main/resources/db/migration");
  private static final Path TABLE_LIST =
      Path.of("src/test/resources/schema/full-release-tables.txt");
  private static final List<String> FULL_RELEASE_TABLES =
      List.of(
          "app_user",
          "app_user_notification_preference",
          "archive_grant",
          "billing_charge_attempt",
          "billing_customer",
          "billing_invoice",
          "billing_reconciliation_entry",
          "company_service_info",
          "contract",
          "contract_document",
          "contract_fee",
          "contract_fee_revision",
          "contract_fee_transaction",
          "contract_followup_task",
          "contract_party",
          "contract_payment_schedule",
          "contract_payment_transaction",
          "contract_revision",
          "contract_status_history",
          "customer",
          "customer_consultation",
          "customer_consultation_revision",
          "customer_demand_condition",
          "customer_demand_region",
          "customer_merge_log",
          "customer_property_interest",
          "deletion_queue",
          "email_verification_token",
          "employment_roster",
          "enterprise_lead",
          "enterprise_lead_status_history",
          "enterprise_quote",
          "entity_lifecycle_event",
          "export_job",
          "import_batch",
          "import_mapping_item",
          "import_mapping_version",
          "import_record_link",
          "import_row_result",
          "import_support_request",
          "incident_log",
          "integration_config",
          "login_history",
          "member_emergency_suspension",
          "monthly_memo",
          "monthly_settlement",
          "notification",
          "office",
          "office_change_request",
          "office_export_table_policy",
          "office_membership",
          "office_membership_status_history",
          "office_owner_transfer",
          "office_registration",
          "office_registration_evidence",
          "office_registration_review_check",
          "office_registration_revision",
          "office_registration_status_history",
          "operator_account",
          "operator_invitation",
          "operator_invitation_role",
          "operator_role_assignment",
          "ops_approval_request",
          "ops_audit_log",
          "ops_business_calendar",
          "ops_raw_access_grant",
          "password_reset_token",
          "platform_announcement",
          "policy_document",
          "price_schedule",
          "privacy_incident",
          "privacy_request",
          "privacy_request_event",
          "property",
          "property_access_approval",
          "property_access_time_slot",
          "property_category",
          "property_custom_field_definition",
          "property_field_value",
          "property_offer",
          "property_owner_occupant",
          "property_photo",
          "rate_limit_counter",
          "refund_request",
          "return_grant",
          "shedlock",
          "subscription",
          "subscription_cancellation",
          "subscription_purchase_consent",
          "support_inquiry",
          "support_inquiry_attachment",
          "support_inquiry_message",
          "system_job_run",
          "task",
          "terms_agreement",
          "terms_catalog",
          "trial_event",
          "visit_approver_link",
          "visit_approver_time_slot",
          "visit_assistant_notice_log",
          "visit_candidate_property",
          "visit_confirmation_delivery",
          "visit_coordination",
          "visit_coordination_status_history",
          "visit_customer_link",
          "visit_customer_time_slot",
          "visit_schedule_item",
          "visit_schedule_item_result_history");

  private static final String V12_NAME = "V12__replace_pilot_schema_with_full_release.sql";

  private static final String[] V1_TO_V11 = {
    "V1__init.sql",
    "V2__create_member_and_office_schema.sql",
    "V3__create_property_and_coordination_schema.sql",
    "V4__create_expiry_task_and_shedlock.sql",
    "V5__enforce_login_history_and_coordination_origin_tenancy.sql",
    "V6__add_auth_policy_and_rate_limit_schema.sql",
    "V7__migrate_property_contract_schema.sql",
    "V8__align_email_change_verification_consumption.sql",
    "V9__align_kakao_final_schema.sql",
    "V10__align_domain_schema.sql",
    "V11__extend_coordination_status_for_visit_complete.sql"
  };

  private static final Map<String, Integer> PRODUCTION_CHECKSUMS =
      Map.ofEntries(
          Map.entry("1", -594239768),
          Map.entry("2", 1239509721),
          Map.entry("3", 238143231),
          Map.entry("4", 87914270),
          Map.entry("5", -1873058121),
          Map.entry("6", -2089355102),
          Map.entry("7", -1724556427),
          Map.entry("8", -521303851),
          Map.entry("9", -1532288716),
          Map.entry("10", -689091471),
          Map.entry("11", 2083762615));

  private Path migrationDir;

  @BeforeEach
  void copyV1ToV11ToTempDir() throws IOException {
    migrationDir = Files.createTempDirectory("sairo-flyway-v12-upgrade-test");
    for (String fileName : V1_TO_V11) {
      Files.copy(SOURCE.resolve(fileName), migrationDir.resolve(fileName));
    }
  }

  @AfterEach
  void cleanupTempDir() throws IOException {
    try (var walk = Files.walk(migrationDir)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.delete(path);
                } catch (IOException ignored) {
                }
              });
    }
  }

  private Flyway flywayFor(Path location) {
    return Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("filesystem:" + location)
        .load();
  }

  private Connection connect() throws Exception {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private void addV12ToTempDir() throws IOException {
    Files.copy(SOURCE.resolve(V12_NAME), migrationDir.resolve(V12_NAME));
  }

  private void seedPilotRows(Connection conn) throws Exception {
    try (Statement st = conn.createStatement()) {
      st.execute("INSERT INTO app_user (kakao_provider_key, name) VALUES ('kakao-1', '파일럿 사용자')");
      st.execute(
          "INSERT INTO office (name, representative_name, business_registration_number,"
              + " real_estate_license_number, phone, address)"
              + " VALUES ('임시사무소', '대표', '0000000000', 'PILOT-1', '000-0000-0000', '임시 주소')");
      st.execute(
          "INSERT INTO office_membership (office_id, user_id, role, status, reviewed_at)"
              + " VALUES (1, 1, 'STAFF', 'APPROVED', now())");
      st.execute(
          "INSERT INTO property (office_id, address, deal_type, property_name, property_status)"
              + " VALUES (1, '서울특별시', 'SALE', '임시 매물', 'NO_CONTRACT')");
    }
  }

  private long count(Connection conn, String sql) throws Exception {
    try (PreparedStatement ps = conn.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getLong(1);
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

  @Test
  void 빈_데이터베이스에서_V1부터_V12까지_순서대로_적용되고_validate를_통과한다() throws Exception {
    addV12ToTempDir();
    Flyway flyway = flywayFor(migrationDir);
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
          .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
    }
  }

  @Test
  void V12는_파일럿_데이터가_있는_V11_스키마를_완전출시_스키마로_교체한다() throws Exception {
    flywayFor(migrationDir).migrate();
    try (Connection conn = connect()) {
      seedPilotRows(conn);
      assertThat(count(conn, "SELECT count(*) FROM property")).isEqualTo(1);
    }

    addV12ToTempDir();
    flywayFor(migrationDir).migrate();

    try (Connection conn = connect()) {
      TreeSet<String> expected = new TreeSet<>(FULL_RELEASE_TABLES);
      assertThat(publicTables(conn)).isEqualTo(expected);
      assertThat(expected).hasSize(108);
      for (String removed :
          List.of(
              "coordination",
              "coordination_candidate_time",
              "coordination_customer_response",
              "coordination_status_history",
              "customer_response_candidate",
              "customer_response_link",
              "expiry_task",
              "property_contract",
              "spring_session",
              "spring_session_attributes")) {
        assertThat(publicTables(conn)).doesNotContain(removed);
      }
      assertThat(count(conn, "SELECT count(*) FROM app_user")).isZero();
      assertThat(count(conn, "SELECT count(*) FROM office")).isZero();
      assertThat(count(conn, "SELECT count(*) FROM office_membership")).isZero();
      assertThat(count(conn, "SELECT count(*) FROM property")).isZero();
    }
  }

  @Test
  void 적용_후_카탈로그의_컬럼_제약_인덱스_IDENTITY_개수가_원본_DDL과_일치한다() throws Exception {
    addV12ToTempDir();
    flywayFor(migrationDir).migrate();

    try (Connection conn = connect()) {
      assertThat(count(conn, columnCountSql())).isEqualTo(1264);
      assertThat(count(conn, constraintCountSql("p"))).isEqualTo(108);
      assertThat(count(conn, constraintCountSql("f"))).isEqualTo(239);
      assertThat(count(conn, constraintCountSql("u"))).isEqualTo(74);
      assertThat(count(conn, constraintCountSql("c"))).isEqualTo(413);
      assertThat(
              count(
                  conn,
                  "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'"
                      + " AND tablename <> 'flyway_schema_history'"))
          .isEqualTo(223);
      assertThat(
              count(
                  conn,
                  "SELECT count(*) FROM information_schema.columns"
                      + " WHERE table_schema = 'public' AND is_identity = 'YES'"))
          .isEqualTo(104);
    }
  }

  @Test
  void V1부터_V11까지의_체크섬은_운영_flyway_이력과_같고_V12_추가_후에도_바뀌지_않는다() throws Exception {
    addV12ToTempDir();
    Flyway flyway = flywayFor(migrationDir);
    flyway.migrate();
    assertThatCode(flyway::validate).doesNotThrowAnyException();

    Map<String, Integer> actual = checksums(flyway);
    for (Map.Entry<String, Integer> expected : PRODUCTION_CHECKSUMS.entrySet()) {
      assertThat(actual.get(expected.getKey()))
          .as("V%s 체크섬", expected.getKey())
          .isEqualTo(expected.getValue());
    }
    assertThat(actual).containsKey("12");
  }

  @Test
  void V12가_중간에_실패하면_트랜잭션이_롤백되어_V11_스키마와_데이터가_그대로_남는다() throws Exception {
    flywayFor(migrationDir).migrate();
    try (Connection conn = connect()) {
      seedPilotRows(conn);
    }

    String v12 = Files.readString(SOURCE.resolve(V12_NAME), StandardCharsets.UTF_8);
    Files.writeString(
        migrationDir.resolve(V12_NAME), v12 + "\nSELECT 1 / 0;\n", StandardCharsets.UTF_8);

    assertThatThrownBy(() -> flywayFor(migrationDir).migrate()).isInstanceOf(Exception.class);

    try (Connection conn = connect()) {
      assertThat(count(conn, "SELECT count(*) FROM property")).isEqualTo(1);
      assertThat(count(conn, "SELECT count(*) FROM app_user")).isEqualTo(1);
      assertThat(publicTables(conn)).contains("coordination", "property_contract");
      assertThat(publicTables(conn)).doesNotContain("property_category", "visit_coordination");
      assertThat(count(conn, "SELECT count(*) FROM flyway_schema_history WHERE version = '12'"))
          .isZero();
    }
  }

  private Map<String, Integer> checksums(Flyway flyway) {
    Map<String, Integer> result = new java.util.TreeMap<>();
    for (MigrationInfo info : flyway.info().all()) {
      result.put(info.getVersion().getVersion(), info.getChecksum());
    }
    return result;
  }

  private String columnCountSql() {
    return "SELECT count(*) FROM information_schema.columns c"
        + " JOIN information_schema.tables t ON t.table_schema = c.table_schema"
        + " AND t.table_name = c.table_name AND t.table_type = 'BASE TABLE'"
        + " WHERE c.table_schema = 'public' AND c.table_name <> 'flyway_schema_history'";
  }

  private String constraintCountSql(String type) {
    return "SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace"
        + " WHERE n.nspname = 'public' AND c.contype = '"
        + type
        + "' AND c.conrelid <> 'flyway_schema_history'::regclass";
  }
}
