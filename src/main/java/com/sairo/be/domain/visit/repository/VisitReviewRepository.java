package com.sairo.be.domain.visit.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class VisitReviewRepository {

  public record Target(
      Long itemId,
      Instant startAt,
      Instant endAt,
      String result,
      Instant actualVisitedAt,
      Instant resultRecordedAt,
      Long requestId,
      Long approvalId,
      Long applicantMembershipId,
      Long applicantOfficeId) {}

  public record Review(
      Long id,
      Long itemId,
      Long requestId,
      Long approvalId,
      Long reviewedMembershipId,
      Long reviewedOfficeId,
      Long approverLinkId,
      String punctuality,
      String advanceContact,
      String asRequested,
      String status,
      Instant deadlineAt,
      Instant submittedAt,
      Instant lastRevisedAt,
      int revisionCount,
      Instant removedAt,
      long version,
      boolean disputed) {}

  public record TargetWithReview(Target target, Review review) {}

  public record Answers(String punctuality, String advanceContact, String asRequested) {}

  public record Dispute(
      Long id,
      Long reviewId,
      Long officeId,
      Long filedByMembershipId,
      String reasonCode,
      String reasonText,
      String status,
      Instant filedAt,
      Instant decidedAt,
      String decisionNote) {}

  public record ReceivedRow(Review review, Instant actualVisitedAt, Long propertyId) {}

  public record OpsRow(
      Dispute dispute,
      Review review,
      Instant actualVisitedAt,
      String approverType,
      Long issuedByMembershipId) {}

  private static final String TARGET =
      "SELECT si.id AS t_item, si.scheduled_start_at, si.scheduled_end_at, si.result,"
          + " si.actual_visited_at, si.result_recorded_at, vr.id AS t_request, vr.approval_id AS"
          + " t_approval, vr.applicant_membership_id, vr.applicant_office_id FROM"
          + " visit_schedule_item si JOIN visit_request vr ON vr.id = si.confirmed_request_id";

  private static final String REVIEW_COLUMNS =
      "r.id, r.schedule_item_id, r.request_id, r.approval_id, r.reviewed_membership_id,"
          + " r.reviewed_office_id, r.approver_link_id, r.punctuality, r.advance_contact,"
          + " r.as_requested, r.status, r.review_deadline_at, r.submitted_at, r.last_revised_at,"
          + " r.revision_count, r.removed_at, r.version, EXISTS (SELECT 1 FROM"
          + " visit_review_dispute d0 WHERE d0.review_id = r.id) AS disputed";

  private static final String TARGET_WITH_REVIEW =
      TARGET.replace(
          " FROM visit_schedule_item si", ", " + REVIEW_COLUMNS + " FROM visit_schedule_item si");

  private static final String DISPUTE_COLUMNS =
      "d.id AS d_id, d.review_id AS d_review, d.office_id AS d_office,"
          + " d.filed_by_membership_id, d.reason_code, d.reason_text, d.status AS d_status,"
          + " d.filed_at, d.decided_at, d.decision_note";

  private final JdbcClient jdbcClient;

  public VisitReviewRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public List<TargetWithReview> listForApprover(Long approvalId, Instant now, Instant windowStart) {
    return jdbcClient
        .sql(
            TARGET_WITH_REVIEW
                + " LEFT JOIN visit_review r ON r.schedule_item_id = si.id"
                + " WHERE vr.approval_id = :approval AND si.result = 'COMPLETED' AND"
                + " (COALESCE(r.review_deadline_at, si.result_recorded_at + interval '14 days') >"
                + " :now OR (r.id IS NOT NULL AND si.actual_visited_at >= :windowStart))"
                + " ORDER BY si.actual_visited_at DESC, si.id DESC")
        .param("approval", approvalId)
        .param("now", Timestamp.from(now))
        .param("windowStart", Timestamp.from(windowStart))
        .query(
            (rs, rowNum) -> {
              Target target = target(rs);
              rs.getLong("id");
              boolean noReview = rs.wasNull();
              return new TargetWithReview(target, noReview ? null : review(rs));
            })
        .list();
  }

  public Optional<Target> lockTarget(Long itemId, Long approvalId) {
    return jdbcClient
        .sql(TARGET + " WHERE si.id = :item AND vr.approval_id = :approval FOR SHARE OF si")
        .param("item", itemId)
        .param("approval", approvalId)
        .query((rs, rowNum) -> target(rs))
        .optional();
  }

  public Optional<Instant> latestPendingDeadline(Long approvalId, Instant now) {
    return jdbcClient
        .sql(
            "SELECT MAX(COALESCE(r.review_deadline_at, si.result_recorded_at + interval '14"
                + " days')) FROM visit_schedule_item si JOIN visit_request vr ON vr.id ="
                + " si.confirmed_request_id LEFT JOIN visit_review r ON r.schedule_item_id ="
                + " si.id WHERE vr.approval_id = :approval AND si.result = 'COMPLETED' AND"
                + " COALESCE(r.review_deadline_at, si.result_recorded_at + interval '14 days') >"
                + " :now AND (r.id IS NULL OR (r.status IN ('ACTIVE','WITHDRAWN') AND"
                + " r.revision_count < 2 AND NOT EXISTS (SELECT 1 FROM visit_review_dispute d"
                + " WHERE d.review_id = r.id)))")
        .param("approval", approvalId)
        .param("now", Timestamp.from(now))
        .query((rs, rowNum) -> Optional.ofNullable(instant(rs.getTimestamp(1))))
        .single();
  }

  public Optional<Review> findByItem(Long itemId) {
    return jdbcClient
        .sql("SELECT " + REVIEW_COLUMNS + " FROM visit_review r WHERE r.schedule_item_id = :item")
        .param("item", itemId)
        .query((rs, rowNum) -> review(rs))
        .optional();
  }

  public Optional<Review> lockByItem(Long itemId) {
    return jdbcClient
        .sql(
            "SELECT "
                + REVIEW_COLUMNS
                + " FROM visit_review r WHERE r.schedule_item_id = :item FOR UPDATE OF r")
        .param("item", itemId)
        .query((rs, rowNum) -> review(rs))
        .optional();
  }

  public Optional<Review> lock(Long reviewId) {
    return jdbcClient
        .sql("SELECT " + REVIEW_COLUMNS + " FROM visit_review r WHERE r.id = :id FOR UPDATE OF r")
        .param("id", reviewId)
        .query((rs, rowNum) -> review(rs))
        .optional();
  }

  public Optional<Long> insert(
      Target target, Answers answers, Long linkId, Instant deadline, Instant now) {
    return jdbcClient
        .sql(
            "INSERT INTO visit_review (schedule_item_id, request_id, approval_id,"
                + " reviewed_membership_id, reviewed_office_id, approver_link_id, punctuality,"
                + " advance_contact, as_requested, status, review_deadline_at, submitted_at,"
                + " last_revised_at, revision_count, updated_at, version) VALUES (:item,"
                + " :request, :approval, :member, :office, :link, :punctuality, :contact,"
                + " :asRequested, 'ACTIVE', :deadline, :now, :now, 0, :now, 1) ON CONFLICT"
                + " (schedule_item_id) DO NOTHING RETURNING id")
        .param("item", target.itemId())
        .param("request", target.requestId())
        .param("approval", target.approvalId())
        .param("member", target.applicantMembershipId())
        .param("office", target.applicantOfficeId())
        .param("link", linkId)
        .param("punctuality", answers.punctuality())
        .param("contact", answers.advanceContact())
        .param("asRequested", answers.asRequested())
        .param("deadline", Timestamp.from(deadline))
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .optional();
  }

  public void revise(Long reviewId, Answers answers, Long linkId, Instant now) {
    jdbcClient
        .sql(
            "UPDATE visit_review SET punctuality = :punctuality, advance_contact = :contact,"
                + " as_requested = :asRequested, status = 'ACTIVE', withdrawn_at = NULL,"
                + " approver_link_id = :link, approver_profile_id = NULL, last_revised_at = :now,"
                + " revision_count = revision_count + 1, updated_at = :now, version = version +"
                + " 1 WHERE id = :id")
        .param("punctuality", answers.punctuality())
        .param("contact", answers.advanceContact())
        .param("asRequested", answers.asRequested())
        .param("link", linkId)
        .param("now", Timestamp.from(now))
        .param("id", reviewId)
        .update();
  }

  public void withdraw(Long reviewId, Long linkId, Instant now) {
    jdbcClient
        .sql(
            "UPDATE visit_review SET status = 'WITHDRAWN', withdrawn_at = :now, approver_link_id"
                + " = :link, approver_profile_id = NULL, last_revised_at = :now, updated_at ="
                + " :now, version = version + 1 WHERE id = :id")
        .param("link", linkId)
        .param("now", Timestamp.from(now))
        .param("id", reviewId)
        .update();
  }

  public void markStatus(Long reviewId, String status, Instant now) {
    jdbcClient
        .sql(
            "UPDATE visit_review SET status = :status, updated_at = :now, version = version + 1"
                + " WHERE id = :id")
        .param("status", status)
        .param("now", Timestamp.from(now))
        .param("id", reviewId)
        .update();
  }

  public void remove(Long reviewId, String reasonCode, Long operatorId, Instant now) {
    jdbcClient
        .sql(
            "UPDATE visit_review SET status = 'REMOVED', removed_at = :now, removed_reason_code"
                + " = :reason, removed_by_operator_id = :operator, updated_at = :now, version ="
                + " version + 1 WHERE id = :id")
        .param("now", Timestamp.from(now))
        .param("reason", reasonCode)
        .param("operator", operatorId)
        .param("id", reviewId)
        .update();
  }

  public List<ReceivedRow> received(
      Long officeId, Long membershipId, Set<String> statuses, int limit, long offset) {
    return jdbcClient
        .sql(
            "SELECT "
                + REVIEW_COLUMNS
                + ", si.actual_visited_at AS item_visited_at, cp.property_id FROM visit_review r"
                + " JOIN visit_schedule_item si ON si.id = r.schedule_item_id JOIN"
                + " visit_candidate_property cp ON cp.id = si.candidate_property_id"
                + receivedWhere(membershipId)
                + " ORDER BY r.last_revised_at DESC, r.id DESC LIMIT :limit OFFSET :offset")
        .param("office", officeId)
        .param("member", membershipId)
        .param("statuses", statuses)
        .param("limit", limit)
        .param("offset", offset)
        .query(
            (rs, rowNum) ->
                new ReceivedRow(
                    review(rs),
                    instant(rs.getTimestamp("item_visited_at")),
                    rs.getLong("property_id")))
        .list();
  }

  public long countReceived(Long officeId, Long membershipId, Set<String> statuses) {
    return jdbcClient
        .sql(
            "SELECT count(*) FROM visit_review r JOIN visit_schedule_item si ON si.id ="
                + " r.schedule_item_id"
                + receivedWhere(membershipId))
        .param("office", officeId)
        .param("member", membershipId)
        .param("statuses", statuses)
        .query(Long.class)
        .single();
  }

  private static String receivedWhere(Long membershipId) {
    return " WHERE r.reviewed_office_id = :office AND r.status IN (:statuses) AND si.result ="
        + " 'COMPLETED'"
        + (membershipId == null ? "" : " AND r.reviewed_membership_id = :member");
  }

  public Optional<Dispute> disputeOf(Long reviewId) {
    return jdbcClient
        .sql("SELECT " + DISPUTE_COLUMNS + " FROM visit_review_dispute d WHERE d.review_id = :r")
        .param("r", reviewId)
        .query((rs, rowNum) -> dispute(rs))
        .optional();
  }

  public Optional<Dispute> findDispute(Long disputeId) {
    return jdbcClient
        .sql("SELECT " + DISPUTE_COLUMNS + " FROM visit_review_dispute d WHERE d.id = :id")
        .param("id", disputeId)
        .query((rs, rowNum) -> dispute(rs))
        .optional();
  }

  public Optional<Dispute> lockDispute(Long disputeId) {
    return jdbcClient
        .sql(
            "SELECT "
                + DISPUTE_COLUMNS
                + " FROM visit_review_dispute d WHERE d.id = :id FOR UPDATE OF d")
        .param("id", disputeId)
        .query((rs, rowNum) -> dispute(rs))
        .optional();
  }

  public Long insertDispute(
      Review review, Long filedBy, String reasonCode, String reasonText, Instant now) {
    return jdbcClient
        .sql(
            "INSERT INTO visit_review_dispute (review_id, office_id, filed_by_membership_id,"
                + " reason_code, reason_text, status, filed_at, version) VALUES (:review,"
                + " :office, :member, :code, :text, 'OPEN', :now, 1) RETURNING id")
        .param("review", review.id())
        .param("office", review.reviewedOfficeId())
        .param("member", filedBy)
        .param("code", reasonCode)
        .param("text", reasonText)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  public void decideDispute(
      Long disputeId, String status, Long operatorId, String note, Instant now) {
    jdbcClient
        .sql(
            "UPDATE visit_review_dispute SET status = :status, decided_at = :now,"
                + " decided_by_operator_id = :operator, decision_note = :note, version = version"
                + " + 1 WHERE id = :id")
        .param("status", status)
        .param("now", Timestamp.from(now))
        .param("operator", operatorId)
        .param("note", note)
        .param("id", disputeId)
        .update();
  }

  public List<OpsRow> opsDisputes(Set<String> statuses, int limit, long offset) {
    return jdbcClient
        .sql(
            "SELECT "
                + DISPUTE_COLUMNS
                + ", "
                + REVIEW_COLUMNS
                + ", si.actual_visited_at AS item_visited_at, a.approver_type,"
                + " l.issued_by_membership_id FROM visit_review_dispute d JOIN visit_review r ON"
                + " r.id = d.review_id JOIN visit_schedule_item si ON si.id = r.schedule_item_id"
                + " JOIN property_access_approval a ON a.id = r.approval_id LEFT JOIN"
                + " visit_approver_property_link l ON l.id = r.approver_link_id WHERE d.status IN"
                + " (:statuses) ORDER BY d.filed_at, d.id LIMIT :limit OFFSET :offset")
        .param("statuses", statuses)
        .param("limit", limit)
        .param("offset", offset)
        .query(
            (rs, rowNum) -> {
              long issuer = rs.getLong("issued_by_membership_id");
              return new OpsRow(
                  dispute(rs),
                  review(rs),
                  instant(rs.getTimestamp("item_visited_at")),
                  rs.getString("approver_type"),
                  rs.wasNull() ? null : issuer);
            })
        .list();
  }

  public long countOpsDisputes(Set<String> statuses) {
    return jdbcClient
        .sql("SELECT count(*) FROM visit_review_dispute d WHERE d.status IN (:statuses)")
        .param("statuses", statuses)
        .query(Long.class)
        .single();
  }

  private static Target target(ResultSet rs) throws SQLException {
    return new Target(
        rs.getLong("t_item"),
        instant(rs.getTimestamp("scheduled_start_at")),
        instant(rs.getTimestamp("scheduled_end_at")),
        rs.getString("result"),
        instant(rs.getTimestamp("actual_visited_at")),
        instant(rs.getTimestamp("result_recorded_at")),
        rs.getLong("t_request"),
        rs.getLong("t_approval"),
        rs.getLong("applicant_membership_id"),
        rs.getLong("applicant_office_id"));
  }

  private static Review review(ResultSet rs) throws SQLException {
    long link = rs.getLong("approver_link_id");
    Long linkId = rs.wasNull() ? null : link;
    return new Review(
        rs.getLong("id"),
        rs.getLong("schedule_item_id"),
        rs.getLong("request_id"),
        rs.getLong("approval_id"),
        rs.getLong("reviewed_membership_id"),
        rs.getLong("reviewed_office_id"),
        linkId,
        rs.getString("punctuality"),
        rs.getString("advance_contact"),
        rs.getString("as_requested"),
        rs.getString("status"),
        instant(rs.getTimestamp("review_deadline_at")),
        instant(rs.getTimestamp("submitted_at")),
        instant(rs.getTimestamp("last_revised_at")),
        rs.getInt("revision_count"),
        instant(rs.getTimestamp("removed_at")),
        rs.getLong("version"),
        rs.getBoolean("disputed"));
  }

  private static Dispute dispute(ResultSet rs) throws SQLException {
    return new Dispute(
        rs.getLong("d_id"),
        rs.getLong("d_review"),
        rs.getLong("d_office"),
        rs.getLong("filed_by_membership_id"),
        rs.getString("reason_code"),
        rs.getString("reason_text"),
        rs.getString("d_status"),
        instant(rs.getTimestamp("filed_at")),
        instant(rs.getTimestamp("decided_at")),
        rs.getString("decision_note"));
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
