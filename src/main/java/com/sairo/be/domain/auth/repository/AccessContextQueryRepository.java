package com.sairo.be.domain.auth.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class AccessContextQueryRepository {

  private final JdbcClient jdbcClient;

  public AccessContextQueryRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Optional<ApprovedRow> findApprovedMembership(Long userId) {
    return jdbcClient
        .sql(
            "SELECT office_id, role FROM office_membership"
                + " WHERE user_id = :userId AND status = 'APPROVED'")
        .param("userId", userId)
        .query((rs, i) -> new ApprovedRow(rs.getLong("office_id"), rs.getString("role")))
        .optional();
  }

  public Optional<Long> findPendingMembershipId(Long userId) {
    return jdbcClient
        .sql("SELECT id FROM office_membership WHERE user_id = :userId AND status = 'PENDING'")
        .param("userId", userId)
        .query(Long.class)
        .optional();
  }

  public Optional<OpenRegistrationRow> findOpenRegistration(Long userId) {
    return jdbcClient
        .sql(
            "SELECT id, status FROM office_registration WHERE applicant_user_id = :userId"
                + " AND status IN ('DRAFT','PENDING_REVIEW','SUPPLEMENT_REQUESTED')")
        .param("userId", userId)
        .query((rs, i) -> new OpenRegistrationRow(rs.getLong("id"), rs.getString("status")))
        .optional();
  }

  public Optional<RejectionRow> findUnresolvedRegistrationRejection(Long userId) {
    return jdbcClient
        .sql(
            "SELECT rejection_reason FROM office_registration WHERE applicant_user_id = :userId"
                + " AND status = 'REJECTED' AND resolved_at IS NULL ORDER BY id DESC LIMIT 1")
        .param("userId", userId)
        .query((rs, i) -> new RejectionRow("REGISTRATION_REJECTED", rs.getString(1)))
        .optional();
  }

  public Optional<RejectionRow> findUnresolvedMembershipRejection(Long userId) {
    return jdbcClient
        .sql(
            "SELECT rejection_reason FROM office_membership WHERE user_id = :userId"
                + " AND status = 'REJECTED' AND resolved_at IS NULL ORDER BY id DESC LIMIT 1")
        .param("userId", userId)
        .query((rs, i) -> new RejectionRow("MEMBERSHIP_REJECTED", rs.getString(1)))
        .optional();
  }

  public Optional<InactiveMembershipRow> findLatestInactiveMembership(Long userId) {
    return jdbcClient
        .sql(
            "SELECT status, revoked_by_type FROM office_membership WHERE user_id = :userId"
                + " AND status IN ('REVOKED','SUSPENDED') ORDER BY id DESC LIMIT 1")
        .param("userId", userId)
        .query(
            (rs, i) ->
                new InactiveMembershipRow(rs.getString("status"), rs.getString("revoked_by_type")))
        .optional();
  }

  public boolean isOfficeRegistrationRevoked(Long officeId) {
    return jdbcClient
            .sql(
                "SELECT count(*) FROM office_registration"
                    + " WHERE resulting_office_id = :officeId AND status = 'REVOKED'")
            .param("officeId", officeId)
            .query(Long.class)
            .single()
        > 0;
  }

  public Optional<SubscriptionRow> findSubscription(Long officeId) {
    return jdbcClient
        .sql(
            "SELECT status, past_due_since, ended_at FROM subscription WHERE office_id = :officeId")
        .param("officeId", officeId)
        .query(
            (rs, i) ->
                new SubscriptionRow(
                    rs.getString("status"),
                    toInstant(rs.getTimestamp("past_due_since")),
                    toInstant(rs.getTimestamp("ended_at"))))
        .optional();
  }

  private static Instant toInstant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  public record ApprovedRow(Long officeId, String role) {}

  public record OpenRegistrationRow(Long id, String status) {}

  public record RejectionRow(String type, String reason) {}

  public record InactiveMembershipRow(String status, String revokedByType) {}

  public record SubscriptionRow(String status, Instant pastDueSince, Instant endedAt) {}
}
