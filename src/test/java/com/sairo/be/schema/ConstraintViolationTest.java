package com.sairo.be.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.PreparedStatement;
import org.junit.jupiter.api.Test;

class ConstraintViolationTest extends AbstractPostgresSchemaTest {

  @Test
  void 소유자가_자기_자신의_소속_변경을_변경_주체로_기록하면_실패한다() {
    assertConstraintViolation(
        "23514",
        "ck_membership_history_actor",
        conn -> {
          long user = insertUser(conn, "소유자", uniqueKakaoKey("owner-self"));
          long office = insertOffice(conn, "사무소", uniqueBizNo());
          long membership = insertApprovedOwner(conn, user, office);

          insertMembershipHistory(conn, membership, "APPROVED", "REVOKED", "OWNER", membership);
        });
  }

  @Test
  void 신청자_주체가_변경_주체_소속을_가지면_실패한다() {
    assertConstraintViolation(
        "23514",
        "ck_membership_history_actor",
        conn -> {
          long user = insertUser(conn, "신청자", uniqueKakaoKey("applicant"));
          long office = insertOffice(conn, "사무소", uniqueBizNo());
          long membership = insertApprovedOwner(conn, user, office);

          insertMembershipHistory(conn, membership, "PENDING", "APPROVED", "APPLICANT", membership);
        });
  }

  @Test
  void 소유자_주체가_변경_주체_소속_없이_기록되면_실패한다() {
    assertConstraintViolation(
        "23514",
        "ck_membership_history_actor",
        conn -> {
          long user = insertUser(conn, "소유자", uniqueKakaoKey("owner-no-actor"));
          long office = insertOffice(conn, "사무소", uniqueBizNo());
          long membership = insertApprovedOwner(conn, user, office);

          insertMembershipHistory(conn, membership, "APPROVED", "REVOKED", "OWNER", null);
        });
  }

  @Test
  void 시스템_주체는_변경_주체_소속_없이_기록할_수_있다() throws Exception {
    withRollback(
        conn -> {
          long user = insertUser(conn, "소유자", uniqueKakaoKey("system-actor"));
          long office = insertOffice(conn, "사무소", uniqueBizNo());
          long membership = insertApprovedOwner(conn, user, office);

          insertMembershipHistory(conn, membership, null, "APPROVED", "SYSTEM", null);

          try (PreparedStatement ps =
              conn.prepareStatement(
                  "SELECT count(*) FROM office_membership_status_history WHERE membership_id = ?")) {
            ps.setLong(1, membership);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
              rs.next();
              assertThat(rs.getInt(1)).isEqualTo(1);
            }
          }
        });
  }

  @Test
  void 한_사무소에_승인된_소유자는_한_명만_허용된다() {
    assertConstraintViolation(
        "23505",
        "uq_office_single_owner",
        conn -> {
          long userA = insertUser(conn, "소유자A", uniqueKakaoKey("owner-a"));
          long userB = insertUser(conn, "소유자B", uniqueKakaoKey("owner-b"));
          long office = insertOffice(conn, "사무소", uniqueBizNo());

          insertApprovedOwner(conn, userA, office);
          insertApprovedOwner(conn, userB, office);
        });
  }

  private void insertMembershipHistory(
      java.sql.Connection conn,
      long membershipId,
      String fromStatus,
      String toStatus,
      String changedByType,
      Long changedByMembershipId)
      throws java.sql.SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO office_membership_status_history"
                + " (membership_id, from_status, to_status, changed_by_type, changed_by_membership_id)"
                + " VALUES (?, ?, ?, ?, ?)")) {
      ps.setLong(1, membershipId);
      ps.setString(2, fromStatus);
      ps.setString(3, toStatus);
      ps.setString(4, changedByType);
      if (changedByMembershipId == null) {
        ps.setNull(5, java.sql.Types.BIGINT);
      } else {
        ps.setLong(5, changedByMembershipId);
      }
      ps.executeUpdate();
    }
  }
}
