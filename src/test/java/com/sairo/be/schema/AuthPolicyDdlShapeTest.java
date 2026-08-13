package com.sairo.be.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;

class AuthPolicyDdlShapeTest extends AbstractSchemaTest {

    @Test
    void 신규_컬럼의_타입_NULL여부_기본값이_참조_DDL과_일치한다() throws Exception {
        withRollback(conn -> {
            assertColumn(conn, "terms_catalog", "required", "boolean", "NO", null);
            assertColumn(conn, "terms_catalog", "withdrawable", "boolean", "NO", null);
            assertColumn(conn, "terms_catalog", "effective_at", "timestamp with time zone", "NO", null);
            assertColumn(conn, "terms_catalog", "content_hash", "character varying", "NO", null);

            assertColumn(conn, "terms_agreement", "member_id", "bigint", "NO", null);
            assertColumn(conn, "terms_agreement", "agreed", "boolean", "NO", null);
            assertColumn(conn, "terms_agreement", "recorded_at", "timestamp with time zone", "NO", "now()");

            assertColumn(conn, "oauth_transaction", "member_id", "bigint", "YES", null);
            assertColumn(conn, "oauth_transaction", "initiating_session_id", "character varying", "YES", null);
            assertColumn(conn, "oauth_transaction", "expires_at", "timestamp with time zone", "NO", null);
            assertColumn(conn, "oauth_transaction", "used_at", "timestamp with time zone", "YES", null);

            assertColumn(conn, "kakao_signup_ticket", "provider_key", "character varying", "NO", null);
            assertColumn(conn, "kakao_signup_ticket", "expires_at", "timestamp with time zone", "NO", null);

            assertColumn(conn, "rate_limit_counter", "bucket_key", "text", "NO", null);
            assertColumn(conn, "rate_limit_counter", "window_started_at", "timestamp with time zone", "NO", null);
            assertColumn(conn, "rate_limit_counter", "request_count", "integer", "NO", "1");

            assertColumn(conn, "email_verification_code", "consumed_at", "timestamp with time zone", "YES", null);
        });
    }

    @Test
    void 신규_FK의_컬럼_순서_참조_대상_삭제정책이_참조_DDL과_정확히_일치한다() throws Exception {
        withRollback(conn -> {
            assertConstraintDefinition(conn, "fk_terms_agreement_catalog",
                    "FOREIGN KEY (terms_code, terms_version) REFERENCES terms_catalog(terms_code, terms_version) ON DELETE RESTRICT");
            assertConstraintDefinition(conn, "oauth_transaction_member_id_fkey",
                    "FOREIGN KEY (member_id) REFERENCES member(id) ON DELETE RESTRICT");
        });
    }

    private void assertColumn(Connection conn, String table, String column,
                               String expectedType, String expectedNullable, String expectedDefault) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT data_type, is_nullable, column_default FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("%s.%s 컬럼이 존재해야 함", table, column).isTrue();
                assertThat(rs.getString("data_type")).as("%s.%s 타입", table, column).isEqualTo(expectedType);
                assertThat(rs.getString("is_nullable")).as("%s.%s NULL 허용 여부", table, column).isEqualTo(expectedNullable);
                String actualDefault = rs.getString("column_default");
                if (expectedDefault == null) {
                    assertThat(actualDefault).as("%s.%s 기본값은 없어야 함", table, column).isNull();
                } else {
                    assertThat(actualDefault).as("%s.%s 기본값", table, column).contains(expectedDefault);
                }
            }
        }
    }

    private void assertConstraintDefinition(Connection conn, String constraintName, String expectedDefinition)
            throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?")) {
            ps.setString(1, constraintName);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("제약 %s가 존재해야 함", constraintName).isTrue();
                String actual = rs.getString(1).replaceAll("\\s+", " ").trim();
                assertThat(actual).as("제약 %s의 정의(컬럼 순서·참조 대상·삭제 정책 포함)", constraintName)
                        .isEqualTo(expectedDefinition);
            }
        }
    }
}
