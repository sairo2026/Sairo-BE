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
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class V8UpgradeTest {

    @Container
    private final PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    private static final String[] V1_TO_V7 = {
            "V1__init.sql",
            "V2__create_member_and_office_schema.sql",
            "V3__create_property_and_coordination_schema.sql",
            "V4__create_expiry_task_and_shedlock.sql",
            "V5__enforce_login_history_and_coordination_origin_tenancy.sql",
            "V6__add_auth_policy_and_rate_limit_schema.sql",
            "V7__migrate_property_contract_schema.sql"
    };
    private static final String V8_NAME = "V8__align_email_change_verification_consumption.sql";

    private Path migrationDir;

    @BeforeEach
    void copyV1ToV7ToTempDir() throws IOException {
        migrationDir = Files.createTempDirectory("sairo-flyway-v8-upgrade-test");
        Path source = Path.of("src/main/resources/db/migration");
        for (String fileName : V1_TO_V7) {
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

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private void addRealV8ToTempDir() throws IOException {
        Path source = Path.of("src/main/resources/db/migration");
        Files.copy(source.resolve(V8_NAME), migrationDir.resolve(V8_NAME));
    }

    @Test
    void V7까지_적용된_상태에서_V8을_추가하면_정상_업그레이드된다() throws Exception {
        Flyway v7 = flywayFor(migrationDir);
        v7.migrate();

        addRealV8ToTempDir();

        Flyway v8 = flywayFor(migrationDir);
        assertThatCode(v8::migrate).doesNotThrowAnyException();
        assertThatCode(v8::validate).doesNotThrowAnyException();

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT version FROM flyway_schema_history ORDER BY installed_rank");
             ResultSet rs = ps.executeQuery()) {
            List<String> versions = new java.util.ArrayList<>();
            while (rs.next()) {
                versions.add(rs.getString(1));
            }
            assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
        }
    }

    @Test
    void V8은_기존_데이터를_보존하고_EMAIL_CHANGE_소비를_허용한다() throws Exception {
        Flyway v7 = flywayFor(migrationDir);
        v7.migrate();

        long memberId;
        long emailChangeCodeId;
        try (Connection conn = connect()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO member (display_name, email) VALUES ('기존회원', 'existing-v8@example.com') RETURNING id")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    memberId = rs.getLong(1);
                }
            }
            emailChangeCodeId = insertVerifiedCode(conn, memberId, "EMAIL_CHANGE", false);
            insertVerifiedCode(conn, memberId, "LOGIN", true);
        }

        addRealV8ToTempDir();
        Flyway v8 = flywayFor(migrationDir);
        assertThatCode(v8::migrate).doesNotThrowAnyException();

        try (Connection conn = connect()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE email_verification_code SET consumed_at = now() WHERE id = ?")) {
                ps.setLong(1, emailChangeCodeId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM member WHERE id = ?")) {
                ps.setLong(1, memberId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("V8 적용 후에도 기존 회원 행이 남아있어야 함").isEqualTo(1);
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM email_verification_code WHERE member_id = ? AND purpose = 'LOGIN' AND consumed_at IS NOT NULL")) {
                ps.setLong(1, memberId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("기존 LOGIN 소비 기록이 보존되어야 함").isEqualTo(1);
                }
            }
        }
    }

    @Test
    void V8_적용_후에도_STEP_UP에는_consumed_at을_기록할_수_없다() throws Exception {
        Flyway v7 = flywayFor(migrationDir);
        v7.migrate();
        addRealV8ToTempDir();
        flywayFor(migrationDir).migrate();

        try (Connection conn = connect()) {
            long memberId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO member (display_name, email) VALUES ('회원', 'step-up-v8@example.com') RETURNING id")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    memberId = rs.getLong(1);
                }
            }

            assertThatThrownBy(() -> insertVerifiedCode(conn, memberId, "STEP_UP", true))
                    .isInstanceOf(SQLException.class)
                    .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("23514"));
        }
    }

    @Test
    void V8_중간에_실패하면_기존_소비_제약이_롤백된다() throws Exception {
        Flyway v7 = flywayFor(migrationDir);
        v7.migrate();

        String realV8 = Files.readString(
                Path.of("src/main/resources/db/migration").resolve(V8_NAME), StandardCharsets.UTF_8);
        String marker = "ALTER TABLE email_verification_code ADD CONSTRAINT ck_email_verification_consumed";
        assertThat(realV8).contains(marker);
        String brokenV8 = realV8.replace(marker,
                "ALTER TABLE this_table_does_not_exist ADD COLUMN x INT;\n\n" + marker);
        Files.writeString(migrationDir.resolve(V8_NAME), brokenV8, StandardCharsets.UTF_8);

        Flyway brokenMigration = flywayFor(migrationDir);
        assertThatThrownBy(brokenMigration::migrate).isInstanceOf(Exception.class);

        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_email_verification_consumed'");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).contains("SIGNUP").contains("LOGIN").doesNotContain("EMAIL_CHANGE");
        }

        Files.delete(migrationDir.resolve(V8_NAME));
        addRealV8ToTempDir();
        assertThatCode(flywayFor(migrationDir)::migrate).doesNotThrowAnyException();
    }

    private long insertVerifiedCode(Connection conn, long memberId, String purpose, boolean consumed) throws SQLException {
        String sql = "INSERT INTO email_verification_code "
                + "(member_id, purpose, target_email, challenge_id, code_hash, status, expires_at, verified_at, consumed_at) "
                + "VALUES (?, ?, ?, ?, 'hash', 'VERIFIED', now() + interval '5 minutes', now(), "
                + (consumed ? "now()" : "NULL") + ") RETURNING id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, memberId);
            ps.setString(2, purpose);
            ps.setString(3, purpose.toLowerCase() + "-" + UUID.randomUUID() + "@example.com");
            ps.setObject(4, UUID.randomUUID());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
