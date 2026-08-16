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
class V7UpgradeTest {

    // 인스턴스 필드(static 아님) — V6UpgradeTest와 동일한 이유로 테스트마다 새 컨테이너를 띄운다.
    @Container
    private final PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    private static final String[] V1_TO_V6 = {
            "V1__init.sql",
            "V2__create_member_and_office_schema.sql",
            "V3__create_property_and_coordination_schema.sql",
            "V4__create_expiry_task_and_shedlock.sql",
            "V5__enforce_login_history_and_coordination_origin_tenancy.sql",
            "V6__add_auth_policy_and_rate_limit_schema.sql"
    };
    private static final String V7_NAME = "V7__migrate_property_contract_schema.sql";

    private Path migrationDir;

    @BeforeEach
    void copyV1ToV6ToTempDir() throws IOException {
        migrationDir = Files.createTempDirectory("sairo-flyway-v7-upgrade-test");
        Path source = Path.of("src/main/resources/db/migration");
        for (String fileName : V1_TO_V6) {
            Files.copy(source.resolve(fileName), migrationDir.resolve(fileName));
        }
    }

    @AfterEach
    void cleanupTempDir() throws IOException {
        try (var walk = Files.walk(migrationDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
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

    private Connection connect() throws java.sql.SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private void addRealV7ToTempDir() throws IOException {
        Path source = Path.of("src/main/resources/db/migration");
        Files.copy(source.resolve(V7_NAME), migrationDir.resolve(V7_NAME));
    }

    @Test
    void V6까지_적용된_상태에서_V7을_추가하면_검증없이_바로_업그레이드된다() throws Exception {
        Flyway v6 = flywayFor(migrationDir);
        v6.migrate();

        addRealV7ToTempDir();

        Flyway v7 = flywayFor(migrationDir);
        assertThatCode(v7::migrate).doesNotThrowAnyException();
        assertThatCode(v7::validate).doesNotThrowAnyException();

        try (Connection conn = connect()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT version FROM flyway_schema_history ORDER BY installed_rank")) {
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> versions = new java.util.ArrayList<>();
                    while (rs.next()) {
                        versions.add(rs.getString(1));
                    }
                    assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7");
                }
            }
        }
    }

    @Test
    void V6_상태에서_무관한_기존_데이터는_V7_적용_후에도_보존된다() throws Exception {
        Flyway v6 = flywayFor(migrationDir);
        v6.migrate();

        long memberId;
        long officeId;
        try (Connection conn = connect()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO member (display_name, email) VALUES ('기존회원', 'existing-v7@example.com') RETURNING id")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    memberId = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO office (name, representative_name, business_registration_number, phone, address_base) "
                            + "VALUES ('기존사무소', '대표', '9876543210', '010-0000-0000', '서울시') RETURNING id")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    officeId = rs.getLong(1);
                }
            }
        }

        addRealV7ToTempDir();
        Flyway v7 = flywayFor(migrationDir);
        assertThatCode(v7::migrate).doesNotThrowAnyException();

        try (Connection conn = connect()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM member WHERE id = ?")) {
                ps.setLong(1, memberId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("V7 적용 후에도 기존 회원 행이 남아있어야 함").isEqualTo(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM office WHERE id = ?")) {
                ps.setLong(1, officeId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("V7 적용 후에도 기존 사무소 행이 남아있어야 함").isEqualTo(1);
                }
            }
            assertTableExists(conn, "property_contract", true);
            assertColumnExists(conn, "property", "has_contract", false);
            assertColumnExists(conn, "property", "contract_expiry_date", false);
            assertColumnExists(conn, "expiry_task", "property_contract_id", true);
        }
    }

    @Test
    void V7_중간에_실패하면_트랜잭션으로_전체가_롤백된다() throws Exception {
        Flyway v6 = flywayFor(migrationDir);
        v6.migrate();

        String realV7 = Files.readString(
                Path.of("src/main/resources/db/migration").resolve(V7_NAME), StandardCharsets.UTF_8);
        String marker = "CREATE TABLE property_contract (";
        assertThat(realV7).as("V7 원본에 %s가 있어야 broken 사본을 만들 수 있음", marker).contains(marker);
        String brokenV7 = realV7.replace(marker,
                "ALTER TABLE this_table_does_not_exist ADD COLUMN x INT;\n\n" + marker);
        Files.writeString(migrationDir.resolve(V7_NAME), brokenV7, StandardCharsets.UTF_8);

        Flyway brokenMigration = flywayFor(migrationDir);
        assertThatThrownBy(brokenMigration::migrate)
                .as("expiry_task DROP까지는 실행된 뒤 실패하는 스크립트이므로, 트랜잭션이 없다면 옛 expiry_task만 사라진 부분 적용이 발생해야 함")
                .isInstanceOf(Exception.class);

        try (Connection conn = connect()) {
            assertTableExists(conn, "expiry_task", true);
            assertColumnExists(conn, "property", "has_contract", true);
            assertTableExists(conn, "property_contract", false);

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM flyway_schema_history WHERE version = '7' AND success = true")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("실패한 V7는 성공 기록을 남기면 안 됨").isEqualTo(0);
                }
            }
        }

        Files.delete(migrationDir.resolve(V7_NAME));
        addRealV7ToTempDir();
        Flyway recovery = flywayFor(migrationDir);
        assertThatCode(recovery::migrate)
                .as("실패 후 정상 V7을 다시 넣으면 처음부터 온전히 적용돼야 함")
                .doesNotThrowAnyException();

        try (Connection conn = connect()) {
            assertTableExists(conn, "property_contract", true);
            assertTableExists(conn, "expiry_task", true);
            assertColumnExists(conn, "expiry_task", "property_contract_id", true);
        }
    }

    private void assertTableExists(Connection conn, String tableName, boolean shouldExist) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name = ?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                int expected = shouldExist ? 1 : 0;
                assertThat(rs.getInt(1)).as("%s 테이블 존재 여부", tableName).isEqualTo(expected);
            }
        }
    }

    private void assertColumnExists(Connection conn, String table, String column, boolean shouldExist) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name=? AND column_name=?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                int expected = shouldExist ? 1 : 0;
                assertThat(rs.getInt(1)).as("%s.%s 컬럼 존재 여부", table, column).isEqualTo(expected);
            }
        }
    }
}
