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
class V6UpgradeTest {

    // 인스턴스 필드(static 아님) — JUnit5 Testcontainers 확장이 테스트 메서드마다 새 컨테이너를 띄워
    // 테스트 간 DB 상태를 공유하지 않는다(이전 버전은 static이라 실행 순서에 결과가 좌우됐음)
    @Container
    private final PostgreSQLContainer postgres =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    private static final String[] V1_TO_V5 = {
            "V1__init.sql",
            "V2__create_member_and_office_schema.sql",
            "V3__create_property_and_coordination_schema.sql",
            "V4__create_expiry_task_and_shedlock.sql",
            "V5__enforce_login_history_and_coordination_origin_tenancy.sql"
    };
    private static final String V6_NAME = "V6__add_auth_policy_and_rate_limit_schema.sql";

    private Path migrationDir;

    @BeforeEach
    void copyV1ToV5ToTempDir() throws IOException {
        migrationDir = Files.createTempDirectory("sairo-flyway-v6-upgrade-test");
        Path source = Path.of("src/main/resources/db/migration");
        for (String fileName : V1_TO_V5) {
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

    private void addRealV6ToTempDir() throws IOException {
        Path source = Path.of("src/main/resources/db/migration");
        Files.copy(source.resolve(V6_NAME), migrationDir.resolve(V6_NAME));
    }

    @Test
    void V5까지_적용된_상태에서_V6를_추가하면_검증없이_바로_업그레이드된다() throws Exception {
        Flyway v5 = flywayFor(migrationDir);
        v5.migrate();

        addRealV6ToTempDir();

        Flyway v6 = flywayFor(migrationDir);
        assertThatCode(v6::migrate).doesNotThrowAnyException();
        assertThatCode(v6::validate).doesNotThrowAnyException();

        try (Connection conn = connect()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT version FROM flyway_schema_history ORDER BY installed_rank")) {
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> versions = new java.util.ArrayList<>();
                    while (rs.next()) {
                        versions.add(rs.getString(1));
                    }
                    assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6");
                }
            }
        }
    }

    @Test
    void V5_상태에서_기존_데이터가_있어도_V6가_안전하게_적용된다() throws Exception {
        Flyway v5 = flywayFor(migrationDir);
        v5.migrate();

        long memberId;
        long officeId;
        try (Connection conn = connect()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO member (display_name, email) VALUES ('기존회원', 'existing@example.com') RETURNING id")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    memberId = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO office (name, representative_name, business_registration_number, phone, address_base) "
                            + "VALUES ('기존사무소', '대표', '1234567890', '010-0000-0000', '서울시') RETURNING id")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    officeId = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO email_verification_code (member_id, purpose, target_email, challenge_id, code_hash, expires_at) "
                            + "VALUES (?, 'LOGIN', 'existing@example.com', gen_random_uuid(), 'hash', now() + interval '5 minutes')")) {
                ps.setLong(1, memberId);
                ps.executeUpdate();
            }
        }

        addRealV6ToTempDir();
        Flyway v6 = flywayFor(migrationDir);
        assertThatCode(v6::migrate).doesNotThrowAnyException();

        try (Connection conn = connect()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM member WHERE id = ?")) {
                ps.setLong(1, memberId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("V6 적용 후에도 기존 회원 행이 남아있어야 함").isEqualTo(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM office WHERE id = ?")) {
                ps.setLong(1, officeId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("V6 적용 후에도 기존 사무소 행이 남아있어야 함").isEqualTo(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT consumed_at FROM email_verification_code WHERE member_id = ?")) {
                ps.setLong(1, memberId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getObject(1)).as("V6가 추가한 consumed_at은 기존 행에서 NULL이어야 함").isNull();
                }
            }
        }
    }

    @Test
    void V6_중간에_실패하면_트랜잭션으로_전체가_롤백된다() throws Exception {
        Flyway v5 = flywayFor(migrationDir);
        v5.migrate();

        String realV6 = Files.readString(
                Path.of("src/main/resources/db/migration").resolve(V6_NAME), StandardCharsets.UTF_8);
        String marker = "CREATE TABLE terms_agreement (";
        assertThat(realV6).as("V6 원본에 %s가 있어야 broken 사본을 만들 수 있음", marker).contains(marker);
        String brokenV6 = realV6.replace(marker,
                "ALTER TABLE this_table_does_not_exist ADD COLUMN x INT;\n\n" + marker);
        Files.writeString(migrationDir.resolve(V6_NAME), brokenV6, StandardCharsets.UTF_8);

        Flyway brokenMigration = flywayFor(migrationDir);
        assertThatThrownBy(brokenMigration::migrate)
                .as("terms_catalog까지는 정상 생성된 뒤 실패하는 스크립트이므로, 트랜잭션이 없다면 terms_catalog만 남는 부분 적용이 발생해야 함")
                .isInstanceOf(Exception.class);

        try (Connection conn = connect()) {
            assertTableDoesNotExist(conn, "terms_catalog");
            assertTableDoesNotExist(conn, "terms_agreement");
            assertTableDoesNotExist(conn, "oauth_transaction");
            assertTableDoesNotExist(conn, "kakao_signup_ticket");
            assertTableDoesNotExist(conn, "rate_limit_counter");
            assertColumnDoesNotExist(conn, "email_verification_code", "consumed_at");
            assertIndexDoesNotExist(conn, "uq_office_membership_member_approved");

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM flyway_schema_history WHERE version = '6' AND success = true")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("실패한 V6는 성공 기록을 남기면 안 됨").isEqualTo(0);
                }
            }
        }

        Files.delete(migrationDir.resolve(V6_NAME));
        addRealV6ToTempDir();
        Flyway recovery = flywayFor(migrationDir);
        assertThatCode(recovery::migrate)
                .as("실패 후 정상 V6를 다시 넣으면 처음부터 온전히 적용돼야 함")
                .doesNotThrowAnyException();

        try (Connection conn = connect()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='terms_catalog'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("복구 후에는 terms_catalog가 존재해야 함").isEqualTo(1);
                }
            }
        }
    }

    private void assertTableDoesNotExist(Connection conn, String tableName) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name = ?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).as("%s는 실패한 V6에서 생성된 채로 남으면 안 됨", tableName).isEqualTo(0);
            }
        }
    }

    private void assertColumnDoesNotExist(Connection conn, String table, String column) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name=? AND column_name=?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).as("%s.%s는 실패한 V6에서 추가된 채로 남으면 안 됨", table, column).isEqualTo(0);
            }
        }
    }

    private void assertIndexDoesNotExist(Connection conn, String indexName) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND indexname = ?")) {
            ps.setString(1, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).as("%s는 실패한 V6에서 생성된 채로 남으면 안 됨", indexName).isEqualTo(0);
            }
        }
    }
}
