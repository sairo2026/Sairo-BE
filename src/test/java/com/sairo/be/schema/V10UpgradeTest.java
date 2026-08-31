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
import java.util.Comparator;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class V10UpgradeTest {

  @Container
  private final PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

  private static final String[] V1_TO_V9 = {
    "V1__init.sql",
    "V2__create_member_and_office_schema.sql",
    "V3__create_property_and_coordination_schema.sql",
    "V4__create_expiry_task_and_shedlock.sql",
    "V5__enforce_login_history_and_coordination_origin_tenancy.sql",
    "V6__add_auth_policy_and_rate_limit_schema.sql",
    "V7__migrate_property_contract_schema.sql",
    "V8__align_email_change_verification_consumption.sql",
    "V9__align_kakao_final_schema.sql"
  };
  private static final String V10_NAME = "V10__align_domain_schema.sql";

  private Path migrationDir;

  @BeforeEach
  void copyV1ToV9ToTempDir() throws IOException {
    migrationDir = Files.createTempDirectory("sairo-flyway-v10-upgrade-test");
    Path source = Path.of("src/main/resources/db/migration");
    for (String fileName : V1_TO_V9) {
      Files.copy(source.resolve(fileName), migrationDir.resolve(fileName));
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

  private void addRealV10ToTempDir() throws IOException {
    Path source = Path.of("src/main/resources/db/migration");
    Files.copy(source.resolve(V10_NAME), migrationDir.resolve(V10_NAME));
  }

  @Test
  void V9까지_적용된_빈_스키마에_V10이_정상_업그레이드된다() throws Exception {
    flywayFor(migrationDir).migrate();
    addRealV10ToTempDir();

    Flyway v10 = flywayFor(migrationDir);
    assertThatCode(v10::migrate).doesNotThrowAnyException();
    assertThatCode(v10::validate).doesNotThrowAnyException();

    try (Connection conn = connect();
        PreparedStatement ps =
            conn.prepareStatement(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank");
        ResultSet rs = ps.executeQuery()) {
      List<String> versions = new java.util.ArrayList<>();
      while (rs.next()) {
        versions.add(rs.getString(1));
      }
      assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
    }
  }

  @Test
  void V10은_회원가입_사업자인증_상태_테이블을_제거하고_신규_coordination_테이블을_만든다() throws Exception {
    flywayFor(migrationDir).migrate();
    addRealV10ToTempDir();
    flywayFor(migrationDir).migrate();

    try (Connection conn = connect()) {
      for (String removed :
          List.of(
              "member",
              "login_identity",
              "oauth_transaction",
              "kakao_signup_ticket",
              "document_move_outbox")) {
        assertThat(tableExists(conn, removed)).as("%s는 V10에서 제거되어야 함", removed).isFalse();
      }
      for (String added :
          List.of(
              "app_user",
              "operator_account",
              "coordination_customer_response",
              "customer_response_candidate",
              "customer_response_link")) {
        assertThat(tableExists(conn, added)).as("%s는 V10에서 존재해야 함", added).isTrue();
      }
      assertThat(tableExists(conn, "spring_session"))
          .as("Spring Session 저장소는 V10과 무관하게 유지되어야 함")
          .isTrue();
      assertThat(tableExists(conn, "spring_session_attributes")).isTrue();
    }
  }

  @Test
  void V10_적용_후_coordination_status_check는_상태_6종만_정확히_허용한다() throws Exception {
    flywayFor(migrationDir).migrate();
    addRealV10ToTempDir();
    flywayFor(migrationDir).migrate();

    try (Connection conn = connect();
        PreparedStatement ps =
            conn.prepareStatement(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'coordination_status_check'");
        ResultSet rs = ps.executeQuery()) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(1))
          .as("coordination_status_check 정의가 상태 6종과 정확히 일치해야 함(추가·누락 없이)")
          .isEqualTo(
              "CHECK (((status)::text = ANY ((ARRAY['TENANT_CHECKING'::character varying, "
                  + "'BUYER_DELIVERY_REQUIRED'::character varying, 'BUYER_CHECKING'::character varying, "
                  + "'FINAL_CONFIRMATION_REQUIRED'::character varying, 'COMPLETED'::character varying, "
                  + "'CANCELLED'::character varying])::text[])))");
    }
  }

  @Test
  void V10_중간에_실패하면_전체가_롤백되고_재실행하면_처음부터_온전히_적용된다() throws Exception {
    flywayFor(migrationDir).migrate();

    String v10 =
        Files.readString(
            Path.of("src/main/resources/db/migration").resolve(V10_NAME), StandardCharsets.UTF_8);
    String marker = "CREATE TABLE coordination (";
    assertThat(v10).as("V10 원본에 %s가 있어야 broken 사본을 만들 수 있음", marker).contains(marker);
    Files.writeString(
        migrationDir.resolve(V10_NAME),
        v10.replace(marker, "SELECT * FROM this_table_does_not_exist;\n\n" + marker),
        StandardCharsets.UTF_8);

    assertThatThrownBy(flywayFor(migrationDir)::migrate).isInstanceOf(Exception.class);

    try (Connection conn = connect()) {
      assertThat(tableExists(conn, "member"))
          .as("실패한 V10은 member 테이블 제거를 포함해 아무 것도 남기면 안 됨")
          .isTrue();
      assertThat(tableExists(conn, "app_user")).isFalse();

      try (PreparedStatement ps =
          conn.prepareStatement(
              "SELECT count(*) FROM flyway_schema_history WHERE version = '10' AND success = true")) {
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          assertThat(rs.getInt(1)).as("실패한 V10은 성공 기록을 남기면 안 됨").isEqualTo(0);
        }
      }
    }

    Files.delete(migrationDir.resolve(V10_NAME));
    addRealV10ToTempDir();
    assertThatCode(flywayFor(migrationDir)::migrate)
        .as("실패 후 정상 V10을 다시 넣으면 처음부터 온전히 적용돼야 함")
        .doesNotThrowAnyException();

    try (Connection conn = connect()) {
      assertThat(tableExists(conn, "app_user")).as("복구 후에는 app_user가 존재해야 함").isTrue();
    }
  }

  private boolean tableExists(Connection conn, String tableName) throws Exception {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?)")) {
      ps.setString(1, tableName);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getBoolean(1);
      }
    }
  }
}
