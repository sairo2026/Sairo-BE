package com.sairo.be.domain.office.repository;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.entity.OfficeMembershipStatus;
import com.sairo.be.domain.office.entity.OfficeRequestSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class MembershipRecordRepository {

  public record Row(
      long id,
      long officeId,
      long userId,
      OfficeMembershipRole role,
      OfficeMembershipStatus status,
      OfficeRequestSource requestSource,
      Instant invitationAcceptedAt,
      Instant invitationExpiresAt,
      String qualificationNumber,
      Boolean employmentReportConfirmed,
      Boolean trainingConfirmed,
      Instant approvedAt,
      String rejectionReason,
      Instant resolvedAt,
      Instant createdAt,
      Instant updatedAt,
      long version) {}

  private static final String COLUMNS =
      "id, office_id, user_id, role, status, request_source, invitation_accepted_at,"
          + " invitation_expires_at, qualification_number, employment_report_confirmed,"
          + " training_confirmed, approved_at, rejection_reason, resolved_at, created_at,"
          + " updated_at, version";
  private static final String BUMP = " updated_at = :now, version = version + 1";

  private final JdbcClient jdbcClient;

  public MembershipRecordRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Optional<Row> find(long id) {
    return jdbcClient
        .sql("SELECT " + COLUMNS + " FROM office_membership WHERE id = :id")
        .param("id", id)
        .query(this::map)
        .optional();
  }

  public Optional<Row> lock(long id) {
    return jdbcClient
        .sql("SELECT " + COLUMNS + " FROM office_membership WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(this::map)
        .optional();
  }

  public Optional<Row> latestOfUser(long userId) {
    return jdbcClient
        .sql(
            "SELECT "
                + COLUMNS
                + " FROM office_membership WHERE user_id = :userId ORDER BY id DESC LIMIT 1")
        .param("userId", userId)
        .query(this::map)
        .optional();
  }

  public Optional<Row> approvedOfUser(long userId) {
    return jdbcClient
        .sql(
            "SELECT "
                + COLUMNS
                + " FROM office_membership WHERE user_id = :userId AND status = 'APPROVED'")
        .param("userId", userId)
        .query(this::map)
        .optional();
  }

  public List<Row> pendingOlderThan(Instant requestCutoff, Instant now) {
    return jdbcClient
        .sql(
            "SELECT "
                + COLUMNS
                + " FROM office_membership WHERE status = 'PENDING' AND ((request_source ="
                + " 'INVITE' AND invitation_expires_at <= :now) OR (request_source <> 'INVITE'"
                + " AND created_at <= :cutoff)) ORDER BY id")
        .param("now", Timestamp.from(now))
        .param("cutoff", Timestamp.from(requestCutoff))
        .query(this::map)
        .list();
  }

  public long insertPending(
      long officeId,
      long userId,
      OfficeMembershipRole role,
      OfficeRequestSource source,
      Instant invitationExpiresAt,
      String qualificationNumber,
      Boolean employmentReportConfirmed,
      Boolean trainingConfirmed,
      Instant now) {
    return jdbcClient
        .sql(
            "INSERT INTO office_membership (office_id, user_id, role, status, request_source,"
                + " invitation_expires_at, qualification_number, employment_report_confirmed,"
                + " training_confirmed, created_at, updated_at) VALUES (:office, :user, :role,"
                + " 'PENDING', :source, :expires, :qualification, :employment, :training, :now,"
                + " :now) RETURNING id")
        .param("office", officeId)
        .param("user", userId)
        .param("role", role.name())
        .param("source", source.name())
        .param("expires", invitationExpiresAt == null ? null : Timestamp.from(invitationExpiresAt))
        .param("qualification", qualificationNumber)
        .param("employment", employmentReportConfirmed)
        .param("training", trainingConfirmed)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  public void approve(long id, Instant now) {
    update(
        id,
        now,
        "status = 'APPROVED', approved_at = :now, suspended_at = NULL, suspension_reason = NULL,");
  }

  public void reject(long id, String reason, Instant now) {
    jdbcClient
        .sql(
            "UPDATE office_membership SET status = 'REJECTED', rejection_reason = :reason,"
                + BUMP
                + " WHERE id = :id")
        .param("reason", reason)
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  public void cancel(long id, Instant now) {
    update(id, now, "status = 'CANCELLED', cancelled_at = :now, resolved_at = :now,");
  }

  public void expire(long id, Instant now) {
    update(id, now, "status = 'EXPIRED', expired_at = :now, resolved_at = :now,");
  }

  public void resolve(long id, Instant now) {
    update(id, now, "resolved_at = :now,");
  }

  public void suspend(long id, String reason, Instant now) {
    jdbcClient
        .sql(
            "UPDATE office_membership SET status = 'SUSPENDED', suspended_at = :now,"
                + " suspension_reason = :reason,"
                + BUMP
                + " WHERE id = :id")
        .param("reason", reason)
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  public void revoke(long id, String revokedBy, String reason, Instant now) {
    jdbcClient
        .sql(
            "UPDATE office_membership SET status = 'REVOKED', revoked_at = :now,"
                + " revoked_by_type = :by, revocation_reason = :reason,"
                + BUMP
                + " WHERE id = :id")
        .param("by", revokedBy)
        .param("reason", reason)
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  public void acceptInvitation(
      long id, String qualificationNumber, boolean employment, boolean training, Instant now) {
    jdbcClient
        .sql(
            "UPDATE office_membership SET invitation_accepted_at = :now,"
                + " qualification_number = :qualification, employment_report_confirmed ="
                + " :employment, training_confirmed = :training,"
                + BUMP
                + " WHERE id = :id")
        .param("qualification", qualificationNumber)
        .param("employment", employment)
        .param("training", training)
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  public void changeRole(long id, OfficeMembershipRole role, Instant now) {
    jdbcClient
        .sql("UPDATE office_membership SET role = :role," + BUMP + " WHERE id = :id")
        .param("role", role.name())
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  public void extendInvitation(long id, Instant expiresAt, Instant now) {
    jdbcClient
        .sql(
            "UPDATE office_membership SET invitation_expires_at = :expires,"
                + BUMP
                + " WHERE id = :id")
        .param("expires", Timestamp.from(expiresAt))
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  public long countApprovedByRole(long officeId, OfficeMembershipRole role) {
    return jdbcClient
        .sql(
            "SELECT count(*) FROM office_membership WHERE office_id = :office AND role = :role"
                + " AND status = 'APPROVED'")
        .param("office", officeId)
        .param("role", role.name())
        .query(Long.class)
        .single();
  }

  private void update(long id, Instant now, String assignments) {
    jdbcClient
        .sql("UPDATE office_membership SET " + assignments + BUMP + " WHERE id = :id")
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  private Row map(java.sql.ResultSet rs, int rowNumber) throws java.sql.SQLException {
    return new Row(
        rs.getLong("id"),
        rs.getLong("office_id"),
        rs.getLong("user_id"),
        OfficeMembershipRole.valueOf(rs.getString("role")),
        OfficeMembershipStatus.valueOf(rs.getString("status")),
        OfficeRequestSource.valueOf(rs.getString("request_source")),
        instant(rs.getTimestamp("invitation_accepted_at")),
        instant(rs.getTimestamp("invitation_expires_at")),
        rs.getString("qualification_number"),
        (Boolean) rs.getObject("employment_report_confirmed"),
        (Boolean) rs.getObject("training_confirmed"),
        instant(rs.getTimestamp("approved_at")),
        rs.getString("rejection_reason"),
        instant(rs.getTimestamp("resolved_at")),
        instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("updated_at")),
        rs.getLong("version"));
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
