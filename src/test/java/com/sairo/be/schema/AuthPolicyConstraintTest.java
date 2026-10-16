package com.sairo.be.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthPolicyConstraintTest extends AbstractPostgresSchemaTest {

  @Test
  void 카탈로그에_없는_약관_코드_버전으로_동의를_기록하면_FK_위반이다() {
    assertConstraintViolation(
        "23503",
        conn -> {
          long user = insertUser(conn, "A", uniqueKakaoKey("terms-fk"));
          insertTermsAgreement(conn, user, "PRIVACY", "존재하지-않는-버전", true);
        });
  }

  @Test
  void 정상_버전은_카탈로그에_등록되고_동의_철회_이벤트가_각각_별도_행으로_쌓인다() throws Exception {
    withRollback(
        conn -> {
          long user = insertUser(conn, "A", uniqueKakaoKey("terms-ok"));
          String code = uniqueTermsCode();
          insertTermsCatalog(conn, code, "2026-08-14");

          insertTermsAgreement(conn, user, code, "2026-08-14", true);
          insertTermsAgreement(conn, user, code, "2026-08-14", false);

          try (PreparedStatement ps =
              conn.prepareStatement(
                  "SELECT agreed FROM terms_agreement WHERE user_id = ? AND terms_code = ? ORDER BY recorded_at, id")) {
            ps.setLong(1, user);
            ps.setString(2, code);
            try (ResultSet rs = ps.executeQuery()) {
              java.util.List<Boolean> agreedValues = new java.util.ArrayList<>();
              while (rs.next()) {
                agreedValues.add(rs.getBoolean(1));
              }
              assertThat(agreedValues)
                  .as("동의와 철회가 각각 별도 이벤트 행으로 남아야 함")
                  .containsExactly(true, false);
            }
          }
        });
  }

  @Test
  void 회원당_APPROVED_사무소_소속은_하나까지_허용된다() throws Exception {
    withRollback(
        conn -> {
          long user = insertUser(conn, "A", uniqueKakaoKey("single-membership"));
          long office = insertOffice(conn, "사무소", uniqueBizNo());
          long membershipId = insertApprovedOwner(conn, user, office);
          assertThat(membershipId).isPositive();
        });
  }

  @Test
  void 동일_회원의_두번째_APPROVED_소속_삽입은_UNIQUE_위반이다() {
    assertConstraintViolation(
        "23505",
        conn -> {
          long user = insertUser(conn, "A", uniqueKakaoKey("double-membership"));
          long officeA = insertOffice(conn, "사무소A", uniqueBizNo());
          insertApprovedOwner(conn, user, officeA);

          long officeB = insertOffice(conn, "사무소B", uniqueBizNo());
          insertApprovedOwner(conn, user, officeB);
        });
  }

  @Test
  void 기존_소속이_REVOKED면_같은_회원이_다른_사무소에_APPROVED로_소속될_수_있다() throws Exception {
    withRollback(
        conn -> {
          long user = insertUser(conn, "A", uniqueKakaoKey("revoked-then-new"));
          long officeA = insertOffice(conn, "사무소A", uniqueBizNo());
          long membershipA = insertApprovedOwner(conn, user, officeA);

          try (PreparedStatement ps =
              conn.prepareStatement(
                  "UPDATE office_membership SET status = 'REVOKED', revoked_at = now(), revocation_reason = '자진 탈퇴', revoked_by_type = 'SELF' WHERE id = ?")) {
            ps.setLong(1, membershipA);
            assertThat(ps.executeUpdate()).isEqualTo(1);
          }

          long officeB = insertOffice(conn, "사무소B", uniqueBizNo());
          long membershipB = insertApprovedOwner(conn, user, officeB);
          assertThat(membershipB).isPositive();
        });
  }

  private void insertTermsCatalog(Connection conn, String code, String version)
      throws java.sql.SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO terms_catalog (terms_code, terms_version, title, category, required, document_url, effective_at, content_hash) "
                + "VALUES (?, ?, '테스트 약관', 'TERMS', true, 'https://example.com/terms', now(), ?)")) {
      ps.setString(1, code);
      ps.setString(2, version);
      ps.setString(3, uniqueHash());
      ps.executeUpdate();
    }
  }

  private void insertTermsAgreement(
      Connection conn, long userId, String code, String version, boolean agreed)
      throws java.sql.SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO terms_agreement (user_id, terms_code, terms_version, agreed) VALUES (?, ?, ?, ?)")) {
      ps.setLong(1, userId);
      ps.setString(2, code);
      ps.setString(3, version);
      ps.setBoolean(4, agreed);
      ps.executeUpdate();
    }
  }

  private String uniqueTermsCode() {
    return "TEST_TERMS_" + UUID.randomUUID().toString().substring(0, 8);
  }

  private String uniqueHash() {
    return UUID.randomUUID().toString().replace("-", "")
        + UUID.randomUUID().toString().replace("-", "");
  }
}
