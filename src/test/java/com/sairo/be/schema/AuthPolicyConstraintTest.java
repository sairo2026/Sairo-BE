package com.sairo.be.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthPolicyConstraintTest extends AbstractSchemaTest {

    @Test
    void 카탈로그에_없는_약관_코드_버전으로_동의를_기록하면_FK_위반이다() {
        assertConstraintViolation("23503", conn -> {
            long member = insertMember(conn, "A", uniqueEmail("terms-fk"));
            insertTermsAgreement(conn, member, "PRIVACY", "존재하지-않는-버전", true);
        });
    }

    @Test
    void 약관_버전이_draft_접두사면_카탈로그_등록이_거부된다() {
        assertConstraintViolation("23514", conn ->
                insertTermsCatalog(conn, uniqueTermsCode(), "draft-2026-08-14"));
    }

    @Test
    void 약관_버전이_시행일_형식이_아니면_카탈로그_등록이_거부된다() {
        assertConstraintViolation("23514", conn ->
                insertTermsCatalog(conn, uniqueTermsCode(), "v1"));
    }

    @Test
    void 약관_버전의_월과_일_범위가_시행일_형식을_벗어나면_등록이_거부된다() {
        assertConstraintViolation("23514", conn ->
                insertTermsCatalog(conn, uniqueTermsCode(), "2026-13-40"));
    }

    @Test
    void 정상_버전은_카탈로그에_등록되고_동의_철회_이벤트가_각각_별도_행으로_쌓인다() throws Exception {
        withRollback(conn -> {
            long member = insertMember(conn, "A", uniqueEmail("terms-ok"));
            String code = uniqueTermsCode();
            insertTermsCatalog(conn, code, "2026-08-14");

            insertTermsAgreement(conn, member, code, "2026-08-14", true);
            insertTermsAgreement(conn, member, code, "2026-08-14", false);

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT agreed FROM terms_agreement WHERE member_id = ? AND terms_code = ? ORDER BY recorded_at, id")) {
                ps.setLong(1, member);
                ps.setString(2, code);
                try (ResultSet rs = ps.executeQuery()) {
                    java.util.List<Boolean> agreedValues = new java.util.ArrayList<>();
                    while (rs.next()) {
                        agreedValues.add(rs.getBoolean(1));
                    }
                    assertThat(agreedValues).as("동의와 철회가 각각 별도 이벤트 행으로 남아야 함").containsExactly(true, false);
                }
            }
        });
    }

    @Test
    void LOGIN_목적_트랜잭션에_회원ID가_있으면_실패한다() {
        assertConstraintViolation("23514", conn -> {
            long member = insertMember(conn, "A", uniqueEmail("oauth-login-fields"));
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO oauth_transaction (state_hash, purpose, member_id, expires_at) "
                            + "VALUES (?, 'LOGIN', ?, now() + interval '10 minutes')")) {
                ps.setString(1, uniqueHash());
                ps.setLong(2, member);
                ps.executeUpdate();
            }
        });
    }

    @Test
    void LINK_목적_트랜잭션에_세션ID가_없으면_실패한다() {
        assertConstraintViolation("23514", conn -> {
            long member = insertMember(conn, "A", uniqueEmail("oauth-link-fields"));
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO oauth_transaction (state_hash, purpose, member_id, step_up_verified_at, expires_at) "
                            + "VALUES (?, 'LINK', ?, now(), now() + interval '10 minutes')")) {
                ps.setString(1, uniqueHash());
                ps.setLong(2, member);
                ps.executeUpdate();
            }
        });
    }

    @Test
    void LOGIN과_LINK_트랜잭션은_각각_정상_조건이면_성공한다() throws Exception {
        withRollback(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO oauth_transaction (state_hash, purpose, expires_at) "
                            + "VALUES (?, 'LOGIN', now() + interval '10 minutes')")) {
                ps.setString(1, uniqueHash());
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }

            long member = insertMember(conn, "A", uniqueEmail("oauth-link-ok"));
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO oauth_transaction (state_hash, purpose, member_id, initiating_session_id, step_up_verified_at, expires_at) "
                            + "VALUES (?, 'LINK', ?, 'session-abc', now(), now() + interval '10 minutes')")) {
                ps.setString(1, uniqueHash());
                ps.setLong(2, member);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
        });
    }

    @Test
    void OAuth_만료시각이_생성시각보다_이르거나_같으면_실패한다() {
        assertConstraintViolation("23514", conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO oauth_transaction (state_hash, purpose, expires_at, created_at) "
                            + "VALUES (?, 'LOGIN', now(), now())")) {
                ps.setString(1, uniqueHash());
                ps.executeUpdate();
            }
        });
    }

    @Test
    void OAuth_사용시각이_생성시각보다_이르면_실패한다() {
        assertConstraintViolation("23514", conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO oauth_transaction (state_hash, purpose, expires_at, created_at, used_at) "
                            + "VALUES (?, 'LOGIN', now() + interval '10 minutes', now(), now() - interval '1 minute')")) {
                ps.setString(1, uniqueHash());
                ps.executeUpdate();
            }
        });
    }

    @Test
    void 카카오_가입티켓_만료시각이_생성시각보다_이르거나_같으면_실패한다() {
        assertConstraintViolation("23514", conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO kakao_signup_ticket (provider_key, ticket_hash, expires_at, created_at) "
                            + "VALUES (?, ?, now(), now())")) {
                ps.setString(1, uniqueKakaoKey());
                ps.setString(2, uniqueHash());
                ps.executeUpdate();
            }
        });
    }

    @Test
    void 카카오_가입티켓_사용시각이_생성시각보다_이르면_실패한다() {
        assertConstraintViolation("23514", conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO kakao_signup_ticket (provider_key, ticket_hash, expires_at, created_at, used_at) "
                            + "VALUES (?, ?, now() + interval '10 minutes', now(), now() - interval '1 minute')")) {
                ps.setString(1, uniqueKakaoKey());
                ps.setString(2, uniqueHash());
                ps.executeUpdate();
            }
        });
    }

    @Test
    void 카카오_가입티켓_정상_발급과_소비는_성공한다() throws Exception {
        withRollback(conn -> {
            String providerKey = uniqueKakaoKey();
            long ticketId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO kakao_signup_ticket (provider_key, ticket_hash, expires_at) "
                            + "VALUES (?, ?, now() + interval '10 minutes') RETURNING id")) {
                ps.setString(1, providerKey);
                ps.setString(2, uniqueHash());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    ticketId = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE kakao_signup_ticket SET used_at = now() WHERE id = ?")) {
                ps.setLong(1, ticketId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
        });
    }

    @Test
    void 인증코드_소비시각은_VERIFIED_상태가_아니면_기록할_수_없다() {
        assertConstraintViolation("23514", conn -> {
            long member = insertMember(conn, "A", uniqueEmail("consumed-not-verified"));
            long codeId = insertActiveCode(conn, member, "LOGIN");
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE email_verification_code SET consumed_at = now() WHERE id = ?")) {
                ps.setLong(1, codeId);
                ps.executeUpdate();
            }
        });
    }

    @Test
    void 인증코드_소비시각은_SIGNUP_LOGIN_목적이_아니면_기록할_수_없다() {
        assertConstraintViolation("23514", conn -> {
            long member = insertMember(conn, "A", uniqueEmail("consumed-wrong-purpose"));
            long codeId = insertActiveCode(conn, member, "STEP_UP");
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE email_verification_code SET status = 'VERIFIED', verified_at = now(), consumed_at = now() WHERE id = ?")) {
                ps.setLong(1, codeId);
                ps.executeUpdate();
            }
        });
    }

    @Test
    void 인증코드_소비시각_기록은_VERIFIED_상태이고_SIGNUP_LOGIN_목적이면_성공한다() throws Exception {
        withRollback(conn -> {
            long member = insertMember(conn, "A", uniqueEmail("consumed-ok"));
            long codeId = insertActiveCode(conn, member, "LOGIN");
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE email_verification_code SET status = 'VERIFIED', verified_at = now(), consumed_at = now() WHERE id = ?")) {
                ps.setLong(1, codeId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
        });
    }

    @Test
    void 회원당_APPROVED_사무소_소속은_하나까지_허용된다() throws Exception {
        withRollback(conn -> {
            long member = insertMember(conn, "A", uniqueEmail("single-membership"));
            long office = insertOffice(conn, "사무소", uniqueBizNo());
            long membershipId = insertApprovedSystemAdmin(conn, member, office, uniqueBizNo());
            assertThat(membershipId).isPositive();
        });
    }

    @Test
    void 동일_회원의_두번째_APPROVED_소속_삽입은_UNIQUE_위반이다() {
        assertConstraintViolation("23505", conn -> {
            long member = insertMember(conn, "A", uniqueEmail("double-membership"));
            long officeA = insertOffice(conn, "사무소A", uniqueBizNo());
            insertApprovedSystemAdmin(conn, member, officeA, uniqueBizNo());

            long officeB = insertOffice(conn, "사무소B", uniqueBizNo());
            insertApprovedSystemAdmin(conn, member, officeB, uniqueBizNo());
        });
    }

    @Test
    void 기존_소속이_REVOKED면_같은_회원이_다른_사무소에_APPROVED로_소속될_수_있다() throws Exception {
        withRollback(conn -> {
            long member = insertMember(conn, "A", uniqueEmail("revoked-then-new"));
            long officeA = insertOffice(conn, "사무소A", uniqueBizNo());
            long membershipA = insertApprovedSystemAdmin(conn, member, officeA, uniqueBizNo());

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE office_membership SET status = 'REVOKED', revoked_at = now(), revocation_reason = '자진 탈퇴' WHERE id = ?")) {
                ps.setLong(1, membershipA);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }

            long officeB = insertOffice(conn, "사무소B", uniqueBizNo());
            long membershipB = insertApprovedSystemAdmin(conn, member, officeB, uniqueBizNo());
            assertThat(membershipB).isPositive();
        });
    }

    private void insertTermsCatalog(Connection conn, String code, String version) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO terms_catalog (terms_code, terms_version, required, withdrawable, effective_at, content_hash) "
                        + "VALUES (?, ?, true, false, now(), ?)")) {
            ps.setString(1, code);
            ps.setString(2, version);
            ps.setString(3, uniqueHash());
            ps.executeUpdate();
        }
    }

    private void insertTermsAgreement(Connection conn, long memberId, String code, String version, boolean agreed)
            throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO terms_agreement (member_id, terms_code, terms_version, agreed) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, memberId);
            ps.setString(2, code);
            ps.setString(3, version);
            ps.setBoolean(4, agreed);
            ps.executeUpdate();
        }
    }

    private long insertActiveCode(Connection conn, long memberId, String purpose) throws java.sql.SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO email_verification_code (member_id, purpose, target_email, challenge_id, code_hash, expires_at) "
                        + "VALUES (?, ?, ?, gen_random_uuid(), 'hash', now() + interval '5 minutes') RETURNING id")) {
            ps.setLong(1, memberId);
            ps.setString(2, purpose);
            ps.setString(3, uniqueEmail("code-target"));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private String uniqueTermsCode() {
        return "TEST_TERMS_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String uniqueHash() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String uniqueKakaoKey() {
        return "kakao-" + UUID.randomUUID();
    }
}
