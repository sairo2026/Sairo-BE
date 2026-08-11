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
            "property",
            "coordination", "coordination_candidate_time", "coordination_status_history",
            "expiry_task", "shedlock"
    );

    @Autowired
    private Flyway flyway;

    @Test
    void flyway_validate_통과한다() {
        flyway.validate();
    }

    @Test
    void V1부터_V4까지_네_마이그레이션이_모두_성공했다() throws Exception {
        withRollback(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")) {
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> versions = new java.util.ArrayList<>();
                    while (rs.next()) {
                        assertThat(rs.getBoolean("success")).as("version %s는 성공해야 함", rs.getString("version")).isTrue();
                        versions.add(rs.getString("version"));
                    }
                    assertThat(versions).containsExactly("1", "2", "3", "4");
                }
            }
        });
    }

    @Test
    void 도메인_테이블_16개가_전부_존재한다() throws Exception {
        withRollback(conn -> assertThat(countTables(conn)).isEqualTo(16));
    }

    @Test
    void 도메인_테이블의_제약_개수는_193개다() throws Exception {
        withRollback(conn -> assertThat(countConstraints(conn)).isEqualTo(193));
    }

    @Test
    void 도메인_테이블의_인덱스_개수는_47개다() throws Exception {
        withRollback(conn -> assertThat(countIndexes(conn)).isEqualTo(47));
    }

    private int countTables(Connection conn) throws java.sql.SQLException {
        return countIn(conn, "SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema='public' AND table_name = ANY(?)");
    }

    private int countConstraints(Connection conn) throws java.sql.SQLException {
        return countIn(conn, "SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_schema='public' AND table_name = ANY(?)");
    }

    private int countIndexes(Connection conn) throws java.sql.SQLException {
        return countIn(conn, "SELECT count(*) FROM pg_indexes "
                + "WHERE schemaname='public' AND tablename = ANY(?)");
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
}
