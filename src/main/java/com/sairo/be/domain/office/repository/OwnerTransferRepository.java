package com.sairo.be.domain.office.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class OwnerTransferRepository {

  public record Row(
      long id,
      long officeId,
      long fromMembershipId,
      long toMembershipId,
      String status,
      Instant requestedAt,
      Instant acceptDeadlineAt,
      Instant acceptedAt,
      Instant completedAt,
      String rejectedReason,
      Instant rejectedAt,
      Instant withdrawnAt) {}

  private static final String COLUMNS =
      "id, office_id, from_membership_id, to_membership_id, status, requested_at,"
          + " accept_deadline_at, accepted_at, completed_at, rejected_reason, rejected_at,"
          + " withdrawn_at";

  private final JdbcClient jdbcClient;

  public OwnerTransferRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Optional<Row> find(long id) {
    return jdbcClient
        .sql("SELECT " + COLUMNS + " FROM office_owner_transfer WHERE id = :id")
        .param("id", id)
        .query(this::map)
        .optional();
  }

  public Optional<Row> lock(long id) {
    return jdbcClient
        .sql("SELECT " + COLUMNS + " FROM office_owner_transfer WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(this::map)
        .optional();
  }

  public Optional<Row> inProgress(long officeId) {
    return jdbcClient
        .sql(
            "SELECT "
                + COLUMNS
                + " FROM office_owner_transfer WHERE office_id = :office AND status IN"
                + " ('PENDING','UNDER_REVIEW')")
        .param("office", officeId)
        .query(this::map)
        .optional();
  }

  public List<Row> overdue(Instant now) {
    return jdbcClient
        .sql(
            "SELECT "
                + COLUMNS
                + " FROM office_owner_transfer WHERE status = 'PENDING' AND accept_deadline_at"
                + " <= :now")
        .param("now", Timestamp.from(now))
        .query(this::map)
        .list();
  }

  public long insert(
      long officeId, long fromMembershipId, long toMembershipId, Instant requestedAt) {
    return jdbcClient
        .sql(
            "INSERT INTO office_owner_transfer (office_id, from_membership_id, to_membership_id,"
                + " status, requested_at, accept_deadline_at) VALUES (:office, :from, :to,"
                + " 'PENDING', :requested, :deadline) RETURNING id")
        .param("office", officeId)
        .param("from", fromMembershipId)
        .param("to", toMembershipId)
        .param("requested", Timestamp.from(requestedAt))
        .param("deadline", Timestamp.from(requestedAt.plus(java.time.Duration.ofDays(7))))
        .query(Long.class)
        .single();
  }

  public void markAccepted(long id, Instant now) {
    set(id, "status = 'UNDER_REVIEW', accepted_at = :now", now);
  }

  public void markRejected(long id, String reason, Instant now) {
    jdbcClient
        .sql(
            "UPDATE office_owner_transfer SET status = 'REJECTED', rejected_at = :now,"
                + " rejected_reason = :reason WHERE id = :id")
        .param("reason", reason)
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  public void markWithdrawn(long id, Instant now) {
    set(id, "status = 'WITHDRAWN', withdrawn_at = :now", now);
  }

  public void markExpired(long id) {
    jdbcClient
        .sql("UPDATE office_owner_transfer SET status = 'EXPIRED' WHERE id = :id")
        .param("id", id)
        .update();
  }

  public void markCompleted(long id, Instant now) {
    set(id, "status = 'COMPLETED', completed_at = :now", now);
  }

  private void set(long id, String assignments, Instant now) {
    jdbcClient
        .sql("UPDATE office_owner_transfer SET " + assignments + " WHERE id = :id")
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  private Row map(java.sql.ResultSet rs, int rowNumber) throws java.sql.SQLException {
    return new Row(
        rs.getLong("id"),
        rs.getLong("office_id"),
        rs.getLong("from_membership_id"),
        rs.getLong("to_membership_id"),
        rs.getString("status"),
        rs.getTimestamp("requested_at").toInstant(),
        rs.getTimestamp("accept_deadline_at").toInstant(),
        instant(rs.getTimestamp("accepted_at")),
        instant(rs.getTimestamp("completed_at")),
        rs.getString("rejected_reason"),
        instant(rs.getTimestamp("rejected_at")),
        instant(rs.getTimestamp("withdrawn_at")));
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
