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
class V9UpgradeTest {

  @Container
  private final PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

  private static final String[] V1_TO_V8 = {
    "V1__init.sql",
    "V2__create_member_and_office_schema.sql",
    "V3__create_property_and_coordination_schema.sql",
    "V4__create_expiry_task_and_shedlock.sql",
    "V5__enforce_login_history_and_coordination_origin_tenancy.sql",
    "V6__add_auth_policy_and_rate_limit_schema.sql",
    "V7__migrate_property_contract_schema.sql",
    "V8__align_email_change_verification_consumption.sql"
  };
  private static final String V9_NAME = "V9__align_kakao_final_schema.sql";

  private Path migrationDir;

  @BeforeEach
  void copyV1ToV8ToTempDir() throws IOException {
    migrationDir = Files.createTempDirectory("sairo-flyway-v9-upgrade-test");
    Path source = Path.of("src/main/resources/db/migration");
    for (String fileName : V1_TO_V8) {
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

  private void addRealV9ToTempDir() throws IOException {
    Path source = Path.of("src/main/resources/db/migration");
    Files.copy(source.resolve(V9_NAME), migrationDir.resolve(V9_NAME));
  }

  @Test
  void V8에서_V9으로_정상_업그레이드된다() throws Exception {
    flywayFor(migrationDir).migrate();
    addRealV9ToTempDir();

    Flyway v9 = flywayFor(migrationDir);
    assertThatCode(v9::migrate).doesNotThrowAnyException();
    assertThatCode(v9::validate).doesNotThrowAnyException();

    try (Connection conn = connect();
        PreparedStatement ps =
            conn.prepareStatement(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank");
        ResultSet rs = ps.executeQuery()) {
      List<String> versions = new java.util.ArrayList<>();
      while (rs.next()) {
        versions.add(rs.getString(1));
      }
      assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
    }

    try (Connection conn = connect()) {
      assertThat(tableExists(conn, "operator")).isTrue();
      assertThat(tableExists(conn, "email_verification_code")).isFalse();
    }
  }

  @Test
  void V9은_기존_회원과_승인된_사무소_신청을_보존한다() throws Exception {
    flywayFor(migrationDir).migrate();

    try (Connection conn = connect()) {
      long memberId;
      try (PreparedStatement ps =
              conn.prepareStatement(
                  "INSERT INTO member (display_name, email) VALUES ('회원', 'v9@example.com') RETURNING id");
          ResultSet rs = ps.executeQuery()) {
        rs.next();
        memberId = rs.getLong(1);
      }

      long officeId;
      try (PreparedStatement ps =
              conn.prepareStatement(
                  "INSERT INTO office (name, representative_name, business_registration_number, real_estate_license_number, phone, address_base) "
                      + "VALUES ('사무소', '대표', '1234567890', 'LICENSE-1', '010-0000-0000', '서울') RETURNING id");
          ResultSet rs = ps.executeQuery()) {
        rs.next();
        officeId = rs.getLong(1);
      }

      try (PreparedStatement ps =
          conn.prepareStatement(
              "INSERT INTO office_registration (applicant_member_id, business_registration_number, status, resulting_office_id, reviewed_at) "
                  + "VALUES (?, '1234567890', 'APPROVED', ?, now())")) {
        ps.setLong(1, memberId);
        ps.setLong(2, officeId);
        ps.executeUpdate();
      }
    }

    addRealV9ToTempDir();
    assertThatCode(flywayFor(migrationDir)::migrate).doesNotThrowAnyException();

    try (Connection conn = connect();
        PreparedStatement ps =
            conn.prepareStatement(
                "SELECT r.requested_office_name, r.real_estate_license_number, m.email "
                    + "FROM office_registration r JOIN member m ON m.id = r.applicant_member_id");
        ResultSet rs = ps.executeQuery()) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(1)).isEqualTo("사무소");
      assertThat(rs.getString(2)).isEqualTo("LICENSE-1");
      assertThat(rs.getString(3)).isEqualTo("v9@example.com");
    }
  }

  @Test
  void 필수_스냅샷을_복구할_수_없는_신청이_있으면_V9은_중단된다() throws Exception {
    flywayFor(migrationDir).migrate();

    try (Connection conn = connect();
        PreparedStatement ps =
            conn.prepareStatement(
                "INSERT INTO member (display_name, email) VALUES ('회원', 'pending-v9@example.com') RETURNING id");
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      try (PreparedStatement insert =
          conn.prepareStatement(
              "INSERT INTO office_registration (applicant_member_id, business_registration_number) VALUES (?, '1234567890')")) {
        insert.setLong(1, rs.getLong(1));
        insert.executeUpdate();
      }
    }

    addRealV9ToTempDir();
    assertThatThrownBy(flywayFor(migrationDir)::migrate).isInstanceOf(Exception.class);

    try (Connection conn = connect()) {
      assertThat(tableExists(conn, "email_verification_code")).isTrue();
      assertThat(tableExists(conn, "operator")).isFalse();
    }
  }

  @Test
  void V9_중간에_실패하면_전체가_롤백된다() throws Exception {
    flywayFor(migrationDir).migrate();

    String v9 =
        Files.readString(
            Path.of("src/main/resources/db/migration").resolve(V9_NAME), StandardCharsets.UTF_8);
    String marker = "CREATE TABLE operator (";
    assertThat(v9).contains(marker);
    Files.writeString(
        migrationDir.resolve(V9_NAME),
        v9.replace(marker, "SELECT * FROM this_table_does_not_exist;\n\n" + marker),
        StandardCharsets.UTF_8);

    assertThatThrownBy(flywayFor(migrationDir)::migrate).isInstanceOf(Exception.class);

    try (Connection conn = connect()) {
      assertThat(tableExists(conn, "email_verification_code")).isTrue();
      assertThat(tableExists(conn, "operator")).isFalse();
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
