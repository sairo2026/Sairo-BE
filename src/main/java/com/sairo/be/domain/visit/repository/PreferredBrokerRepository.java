package com.sairo.be.domain.visit.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class PreferredBrokerRepository {

  public record Preferred(
      Long id,
      Long approvalId,
      Long brokerMembershipId,
      Long brokerOfficeId,
      Instant designatedAt,
      Instant releasedAt) {}

  public record Candidate(
      Long membershipId, Long officeId, Long sourceRequestId, Long sourceScheduleItemId) {}

  public record Source(Long membershipId, Long officeId) {}

  private static final String COLUMNS =
      "SELECT p.id, p.approval_id, p.broker_membership_id, m.office_id, p.designated_at,"
          + " p.released_at FROM visit_preferred_broker p JOIN office_membership m ON m.id ="
          + " p.broker_membership_id";

  private final JdbcClient jdbcClient;

  public PreferredBrokerRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public List<Preferred> active(Long approvalId) {
    return jdbcClient
        .sql(
            COLUMNS
                + " WHERE p.approval_id = :approval AND p.released_at IS NULL ORDER BY"
                + " p.designated_at DESC, p.id DESC")
        .param("approval", approvalId)
        .query((rs, rowNum) -> preferred(rs))
        .list();
  }

  public Optional<Preferred> lock(Long preferredId, Long approvalId) {
    return jdbcClient
        .sql(COLUMNS + " WHERE p.id = :id AND p.approval_id = :approval FOR UPDATE OF p")
        .param("id", preferredId)
        .param("approval", approvalId)
        .query((rs, rowNum) -> preferred(rs))
        .optional();
  }

  public List<Candidate> candidates(Long approvalId) {
    return jdbcClient
        .sql(
            "SELECT DISTINCT ON (vr.applicant_membership_id) vr.applicant_membership_id,"
                + " vr.applicant_office_id, vr.id FROM visit_request vr WHERE vr.approval_id ="
                + " :approval AND NOT EXISTS (SELECT 1 FROM visit_preferred_broker p WHERE"
                + " p.approval_id = vr.approval_id AND p.broker_membership_id ="
                + " vr.applicant_membership_id AND p.released_at IS NULL) ORDER BY"
                + " vr.applicant_membership_id, vr.submitted_at DESC, vr.id DESC")
        .param("approval", approvalId)
        .query(
            (rs, rowNum) ->
                new Candidate(
                    rs.getLong("applicant_membership_id"),
                    rs.getLong("applicant_office_id"),
                    rs.getLong("id"),
                    null))
        .list();
  }

  public Optional<Source> requestSource(Long requestId, Long approvalId) {
    return jdbcClient
        .sql(
            "SELECT applicant_membership_id, applicant_office_id FROM visit_request WHERE id ="
                + " :id AND approval_id = :approval")
        .param("id", requestId)
        .param("approval", approvalId)
        .query(
            (rs, rowNum) ->
                new Source(
                    rs.getLong("applicant_membership_id"), rs.getLong("applicant_office_id")))
        .optional();
  }

  public Optional<Source> visitSource(Long scheduleItemId, Long approvalId) {
    return jdbcClient
        .sql(
            "SELECT vr.applicant_membership_id, vr.applicant_office_id FROM visit_schedule_item"
                + " si JOIN visit_request vr ON vr.id = si.confirmed_request_id WHERE si.id = :id"
                + " AND vr.approval_id = :approval AND si.result = 'COMPLETED'")
        .param("id", scheduleItemId)
        .param("approval", approvalId)
        .query(
            (rs, rowNum) ->
                new Source(
                    rs.getLong("applicant_membership_id"), rs.getLong("applicant_office_id")))
        .optional();
  }

  public int countActive(Long approvalId) {
    return jdbcClient
        .sql(
            "SELECT count(*) FROM visit_preferred_broker WHERE approval_id = :approval AND"
                + " released_at IS NULL")
        .param("approval", approvalId)
        .query(Integer.class)
        .single();
  }

  public boolean isActive(Long approvalId, Long membershipId) {
    return jdbcClient
            .sql(
                "SELECT count(*) FROM visit_preferred_broker WHERE approval_id = :approval AND"
                    + " broker_membership_id = :member AND released_at IS NULL")
            .param("approval", approvalId)
            .param("member", membershipId)
            .query(Integer.class)
            .single()
        > 0;
  }

  public Long insert(
      Long approvalId,
      Long membershipId,
      Long linkId,
      Long profileId,
      Long sourceRequestId,
      Long sourceScheduleItemId,
      Instant now) {
    return jdbcClient
        .sql(
            "INSERT INTO visit_preferred_broker (approval_id, broker_membership_id,"
                + " designated_via_link_id, designated_via_profile_id, source_request_id,"
                + " source_schedule_item_id, designated_at) VALUES (:approval, :member, :link,"
                + " :profile, :request, :item, :now) RETURNING id")
        .param("approval", approvalId)
        .param("member", membershipId)
        .param("link", linkId)
        .param("profile", profileId)
        .param("request", sourceRequestId)
        .param("item", sourceScheduleItemId)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  public void release(Long preferredId, Long linkId, Long profileId, Instant now) {
    jdbcClient
        .sql(
            "UPDATE visit_preferred_broker SET released_at = :now, released_via_link_id = :link,"
                + " released_via_profile_id = :profile WHERE id = :id")
        .param("now", Timestamp.from(now))
        .param("link", linkId)
        .param("profile", profileId)
        .param("id", preferredId)
        .update();
  }

  private static Preferred preferred(ResultSet rs) throws SQLException {
    Timestamp released = rs.getTimestamp("released_at");
    return new Preferred(
        rs.getLong("id"),
        rs.getLong("approval_id"),
        rs.getLong("broker_membership_id"),
        rs.getLong("office_id"),
        rs.getTimestamp("designated_at").toInstant(),
        released == null ? null : released.toInstant());
  }
}
