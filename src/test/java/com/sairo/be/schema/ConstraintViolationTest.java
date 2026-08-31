package com.sairo.be.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;

class ConstraintViolationTest extends AbstractSchemaTest {

  @Test
  void 다른_사무소의_매물을_조율에_연결하면_실패한다() {
    assertConstraintViolation(
        null,
        conn -> {
          long userA = insertUser(conn, "A", uniqueKakaoKey("a"));
          long officeA = insertOffice(conn, "사무소A", uniqueBizNo());
          long membershipA = insertApprovedSystemAdmin(conn, userA, officeA, uniqueBizNo());

          long officeB = insertOffice(conn, "사무소B", uniqueBizNo());
          long propertyB = insertProperty(conn, officeB);

          insertCoordination(conn, officeA, propertyB, membershipA);
        });
  }

  @Test
  void 다른_조율의_후보시간을_확정하면_실패한다() {
    assertConstraintViolation(
        null,
        "fk_confirmed_candidate",
        conn -> {
          long user = insertUser(conn, "A", uniqueKakaoKey("a"));
          long office = insertOffice(conn, "사무소A", uniqueBizNo());
          long membership = insertApprovedSystemAdmin(conn, user, office, uniqueBizNo());
          long property = insertProperty(conn, office);

          long coordination1 = insertCoordination(conn, office, property, membership);
          long coordination2 = insertCoordination(conn, office, property, membership);
          long candidateOfCoordination2 = insertCandidateTime(conn, coordination2);

          try (PreparedStatement ps =
              conn.prepareStatement(
                  "UPDATE coordination SET confirmed_candidate_time_id = ?, confirmed_at = now(), scheduled_at = now(), status = 'COMPLETED' WHERE id = ?")) {
            ps.setLong(1, candidateOfCoordination2);
            ps.setLong(2, coordination1);
            ps.executeUpdate();
          }
        });
  }

  @Test
  void 조율_상태와_시각_컬럼이_안맞으면_실패한다() {
    assertConstraintViolation(
        null,
        conn -> {
          long user = insertUser(conn, "A", uniqueKakaoKey("a"));
          long office = insertOffice(conn, "사무소", uniqueBizNo());
          long membership = insertApprovedSystemAdmin(conn, user, office, uniqueBizNo());
          long property = insertProperty(conn, office);

          try (PreparedStatement ps =
              conn.prepareStatement(
                  "INSERT INTO coordination (office_id, property_id, created_by_membership_id, status) "
                      + "VALUES (?, ?, ?, 'COMPLETED')")) {
            ps.setLong(1, office);
            ps.setLong(2, property);
            ps.setLong(3, membership);
            ps.executeUpdate();
          }
        });
  }

  @Test
  void 신청자_본인이_자기_신청을_승인한_것처럼_기록하면_실패한다() {
    assertConstraintViolation(
        null,
        conn -> {
          long user = insertUser(conn, "신청자", uniqueKakaoKey("applicant"));
          long office = insertOffice(conn, "사무소", uniqueBizNo());
          long membership = insertApprovedSystemAdmin(conn, user, office, uniqueBizNo());

          try (PreparedStatement ps =
              conn.prepareStatement(
                  "INSERT INTO office_membership_status_history (membership_id, from_status, to_status, changed_by_type, changed_by_membership_id) "
                      + "VALUES (?, 'PENDING', 'APPROVED', 'ADMIN', ?)")) {
            ps.setLong(1, membership);
            ps.setLong(2, membership);
            ps.executeUpdate();
          }
        });
  }

  @Test
  void 관리자_본인의_소속_회수를_자기참조로_기록하면_실패한다() {
    assertConstraintViolation(
        null,
        conn -> {
          long user = insertUser(conn, "관리자", uniqueKakaoKey("admin"));
          long office = insertOffice(conn, "사무소", uniqueBizNo());
          long membership = insertApprovedSystemAdmin(conn, user, office, uniqueBizNo());

          try (PreparedStatement ps =
              conn.prepareStatement(
                  "INSERT INTO office_membership_status_history (membership_id, from_status, to_status, changed_by_type, changed_by_membership_id) "
                      + "VALUES (?, 'APPROVED', 'REVOKED', 'ADMIN', ?)")) {
            ps.setLong(1, membership);
            ps.setLong(2, membership);
            ps.executeUpdate();
          }
        });
  }

  @Test
  void 최초_ADMIN을_SYSTEM_주체로_생성할_수_있다() throws Exception {
    withRollback(
        conn -> {
          long user = insertUser(conn, "최초관리자", uniqueKakaoKey("first-admin"));
          long office = insertOffice(conn, "신규사무소", uniqueBizNo());
          long membershipId = insertApprovedSystemAdmin(conn, user, office, uniqueBizNo());
          assertThat(membershipId).isPositive();
        });
  }

  @Test
  void 같은_계약에_D90_업무가_중복되면_실패한다() {
    assertConstraintViolation(
        null,
        conn -> {
          long office = insertOffice(conn, "사무소", uniqueBizNo());
          long user = insertUser(conn, "A", uniqueKakaoKey("a"));
          insertApprovedSystemAdmin(conn, user, office, uniqueBizNo());
          long property = insertProperty(conn, office);
          long contractId = insertActiveContract(conn, office, property, "MONTHLY", "2027-01-01");

          insertOpenExpiryTask(conn, office, property, contractId, "2026-10-03");
          insertOpenExpiryTask(conn, office, property, contractId, "2026-10-03");
        });
  }

  private long insertProperty(Connection conn, long officeId) throws java.sql.SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO property (office_id, address, deal_type) "
                + "VALUES (?, '서울시 어딘가', 'MONTHLY') RETURNING id")) {
      ps.setLong(1, officeId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long insertActiveContract(
      Connection conn, long officeId, long propertyId, String dealType, String contractEndDate)
      throws java.sql.SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO property_contract (office_id, property_id, deal_type, contract_start_date, contract_end_date, "
                + "owner_party_name, owner_party_phone, counterparty_name, counterparty_phone, deposit_amount) "
                + "VALUES (?, ?, ?, CURRENT_DATE, ?::date, '임대인', '010-0000-0000', '임차인', '010-1111-1111', 100000000) RETURNING id")) {
      ps.setLong(1, officeId);
      ps.setLong(2, propertyId);
      ps.setString(3, dealType);
      ps.setString(4, contractEndDate);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long insertCoordination(
      Connection conn, long officeId, long propertyId, long createdByMembershipId)
      throws java.sql.SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO coordination (office_id, property_id, created_by_membership_id, status) "
                + "VALUES (?, ?, ?, 'TENANT_CHECKING') RETURNING id")) {
      ps.setLong(1, officeId);
      ps.setLong(2, propertyId);
      ps.setLong(3, createdByMembershipId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long insertCandidateTime(Connection conn, long coordinationId)
      throws java.sql.SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO coordination_candidate_time (coordination_id, starts_at) "
                + "VALUES (?, now() + interval '1 day') RETURNING id")) {
      ps.setLong(1, coordinationId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private void insertOpenExpiryTask(
      Connection conn, long officeId, long propertyId, long contractId, String targetDate)
      throws java.sql.SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO expiry_task (office_id, property_id, contract_id, target_date) "
                + "VALUES (?, ?, ?, ?::date)")) {
      ps.setLong(1, officeId);
      ps.setLong(2, propertyId);
      ps.setLong(3, contractId);
      ps.setString(4, targetDate);
      ps.executeUpdate();
    }
  }
}
