package com.sairo.be.domain.office.service;

import com.sairo.be.global.support.AdvisoryLock;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class OfficeWithdrawalService {

  private static final String REASON = "회원 탈퇴";

  private final JdbcClient jdbcClient;
  private final AdvisoryLock advisoryLock;

  public OfficeWithdrawalService(JdbcClient jdbcClient, AdvisoryLock advisoryLock) {
    this.jdbcClient = jdbcClient;
    this.advisoryLock = advisoryLock;
  }

  @Transactional(readOnly = true)
  public boolean isOwner(Long userId) {
    Long count =
        jdbcClient
            .sql(
                "SELECT count(*) FROM office_membership"
                    + " WHERE user_id = :userId AND status = 'APPROVED' AND role = 'OWNER'")
            .param("userId", userId)
            .query(Long.class)
            .single();
    return count > 0;
  }

  @Transactional
  public void releaseForWithdrawal(Long userId, Instant now) {
    Timestamp at = Timestamp.from(now);
    cancelOpenRegistrations(userId, at);
    cancelPendingMemberships(userId, at);
    rejectCandidateTransfers(userId, at);
    revokeApprovedMemberships(userId, at);
    assignWithdrawnAliases(userId);
  }

  private void cancelOpenRegistrations(Long userId, Timestamp at) {
    var rows =
        jdbcClient
            .sql(
                "SELECT id, status FROM office_registration WHERE applicant_user_id = :userId"
                    + " AND status IN ('DRAFT','PENDING_REVIEW','SUPPLEMENT_REQUESTED')")
            .param("userId", userId)
            .query((rs, i) -> new Object[] {rs.getLong("id"), rs.getString("status")})
            .list();
    for (Object[] row : rows) {
      jdbcClient
          .sql(
              "UPDATE office_registration SET status = 'CANCELLED', cancelled_at = :at,"
                  + " supplement_reason = NULL, supplement_due_at = NULL, updated_at = :at,"
                  + " version = version + 1 WHERE id = :id")
          .param("at", at)
          .param("id", row[0])
          .update();
      jdbcClient
          .sql(
              "INSERT INTO office_registration_status_history (registration_id, from_status,"
                  + " to_status, changed_by_type, reason, created_at)"
                  + " VALUES (:id, :from, 'CANCELLED', 'SYSTEM', :reason, :at)")
          .param("id", row[0])
          .param("from", row[1])
          .param("reason", REASON)
          .param("at", at)
          .update();
    }
  }

  private void rejectCandidateTransfers(Long userId, Timestamp at) {
    jdbcClient
        .sql(
            "UPDATE office_owner_transfer SET status = 'REJECTED', rejected_at = :at,"
                + " rejected_reason = :reason WHERE status IN ('PENDING','UNDER_REVIEW')"
                + " AND to_membership_id IN (SELECT id FROM office_membership WHERE user_id ="
                + " :userId)")
        .param("at", at)
        .param("reason", REASON)
        .param("userId", userId)
        .update();
  }

  private void cancelPendingMemberships(Long userId, Timestamp at) {
    for (Long id : membershipIds(userId, "PENDING")) {
      jdbcClient
          .sql(
              "UPDATE office_membership SET status = 'CANCELLED', cancelled_at = :at,"
                  + " resolved_at = :at, updated_at = :at, version = version + 1 WHERE id = :id")
          .param("at", at)
          .param("id", id)
          .update();
      recordMembershipHistory(id, "PENDING", "CANCELLED", at);
    }
  }

  private void revokeApprovedMemberships(Long userId, Timestamp at) {
    for (Long id : membershipIds(userId, "APPROVED")) {
      jdbcClient
          .sql(
              "UPDATE office_membership SET status = 'REVOKED', revoked_at = :at,"
                  + " revoked_by_type = 'SELF', revocation_reason = :reason, updated_at = :at,"
                  + " version = version + 1 WHERE id = :id")
          .param("at", at)
          .param("reason", REASON)
          .param("id", id)
          .update();
      recordMembershipHistory(id, "APPROVED", "REVOKED", at);
    }
  }

  private void recordMembershipHistory(Long id, String from, String to, Timestamp at) {
    jdbcClient
        .sql(
            "INSERT INTO office_membership_status_history (membership_id, from_status, to_status,"
                + " changed_by_type, reason, created_at)"
                + " VALUES (:id, :from, :to, 'SYSTEM', :reason, :at)")
        .param("id", id)
        .param("from", from)
        .param("to", to)
        .param("reason", REASON)
        .param("at", at)
        .update();
  }

  private List<Long> membershipIds(Long userId, String status) {
    return jdbcClient
        .sql("SELECT id FROM office_membership WHERE user_id = :userId AND status = :status")
        .param("userId", userId)
        .param("status", status)
        .query(Long.class)
        .list();
  }

  private void assignWithdrawnAliases(Long userId) {
    List<Long> officeIds =
        jdbcClient
            .sql(
                "SELECT DISTINCT office_id FROM office_membership WHERE user_id = :userId"
                    + " AND withdrawn_alias_no IS NULL ORDER BY office_id")
            .param("userId", userId)
            .query(Long.class)
            .list();
    for (Long officeId : officeIds) {
      advisoryLock.lockForTransaction("withdrawn-alias:" + officeId);
      Integer next =
          jdbcClient
              .sql(
                  "SELECT COALESCE(max(withdrawn_alias_no), 0) + 1 FROM office_membership"
                      + " WHERE office_id = :officeId")
              .param("officeId", officeId)
              .query(Integer.class)
              .single();
      jdbcClient
          .sql(
              "UPDATE office_membership SET withdrawn_alias_no = :alias"
                  + " WHERE office_id = :officeId AND user_id = :userId")
          .param("alias", next)
          .param("officeId", officeId)
          .param("userId", userId)
          .update();
    }
  }
}
