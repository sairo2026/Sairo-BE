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
public class ScheduleResultRepository {

  public record ItemHead(
      Long id,
      Long coordinationId,
      Long officeId,
      Long candidatePropertyId,
      Long propertyId,
      Instant startAt,
      Instant endAt,
      String result,
      Instant actualVisitedAt,
      Instant resultRecordedAt,
      Long confirmedRequestId) {

    public boolean scheduled() {
      return "SCHEDULED".equals(result);
    }
  }

  public record HistoryRow(
      Long id,
      String previousResult,
      String result,
      Instant previousActualVisitedAt,
      Instant actualVisitedAt,
      String correctionReason,
      Instant effectiveAt,
      Instant recordedAt,
      Long recordedByMembershipId) {}

  public record HistoryInsert(
      Long itemId,
      String previousResult,
      String result,
      Instant previousActualVisitedAt,
      Instant actualVisitedAt,
      Instant effectiveAt,
      Instant recordedAt,
      Long recordedByMembershipId,
      String correctionReason,
      Long supersedesHistoryId,
      String cancelReasonCode) {}

  public record ClosedRequest(Long requestId, Long applicantMembershipId) {}

  public record ApprovalScheduleRow(
      Long itemId,
      Long coordinationId,
      Instant startAt,
      Instant endAt,
      String result,
      Long requestId,
      Long applicantOfficeId,
      Long applicantMembershipId,
      Long propertyOfficeId) {}

  private static final String HEAD =
      "SELECT si.id, si.coordination_id, si.office_id, si.candidate_property_id,"
          + " cp.property_id, si.scheduled_start_at, si.scheduled_end_at, si.result,"
          + " si.actual_visited_at, si.result_recorded_at, si.confirmed_request_id FROM"
          + " visit_schedule_item si JOIN visit_candidate_property cp ON cp.id ="
          + " si.candidate_property_id";

  private final JdbcClient jdbcClient;

  public ScheduleResultRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Optional<ItemHead> findItem(Long itemId) {
    return jdbcClient
        .sql(HEAD + " WHERE si.id = :id")
        .param("id", itemId)
        .query((rs, rowNum) -> head(rs))
        .optional();
  }

  public List<ItemHead> scheduledOf(Long coordinationId) {
    return jdbcClient
        .sql(
            HEAD
                + " WHERE si.coordination_id = :coordination AND si.result = 'SCHEDULED' ORDER"
                + " BY si.scheduled_start_at NULLS LAST, si.id")
        .param("coordination", coordinationId)
        .query((rs, rowNum) -> head(rs))
        .list();
  }

  public Optional<Long> latestHistoryId(Long itemId) {
    return jdbcClient
        .sql(
            "SELECT h.id FROM visit_schedule_item_result_history h WHERE h.schedule_item_id ="
                + " :item AND NOT EXISTS (SELECT 1 FROM visit_schedule_item_result_history n"
                + " WHERE n.supersedes_history_id = h.id) ORDER BY h.id DESC LIMIT 1")
        .param("item", itemId)
        .query(Long.class)
        .optional();
  }

  public Long insertHistory(HistoryInsert row) {
    return jdbcClient
        .sql(
            "INSERT INTO visit_schedule_item_result_history (schedule_item_id, previous_result,"
                + " result, previous_actual_visited_at, actual_visited_at, effective_at,"
                + " recorded_at, recorded_by_membership_id, correction_reason,"
                + " supersedes_history_id, cancel_reason_code) VALUES (:item, :previous,"
                + " :result, :previousActual, :actual, :effective, :recorded, :recorder,"
                + " :reason, :supersedes, :cancelCode) RETURNING id")
        .param("item", row.itemId())
        .param("previous", row.previousResult())
        .param("result", row.result())
        .param("previousActual", timestamp(row.previousActualVisitedAt()))
        .param("actual", timestamp(row.actualVisitedAt()))
        .param("effective", timestamp(row.effectiveAt()))
        .param("recorded", timestamp(row.recordedAt()))
        .param("recorder", row.recordedByMembershipId())
        .param("reason", row.correctionReason())
        .param("supersedes", row.supersedesHistoryId())
        .param("cancelCode", row.cancelReasonCode())
        .query(Long.class)
        .single();
  }

  public void updateItem(
      Long itemId,
      String result,
      Instant recordedAt,
      Instant actualVisitedAt,
      Long recordedBy,
      String correctionReason,
      String previousResult) {
    jdbcClient
        .sql(
            "UPDATE visit_schedule_item SET result = :result, result_recorded_at = :recorded,"
                + " actual_visited_at = :actual, result_recorded_by_membership_id = :recorder,"
                + " correction_reason = :reason, previous_result = :previous WHERE id = :id")
        .param("result", result)
        .param("recorded", timestamp(recordedAt))
        .param("actual", timestamp(actualVisitedAt))
        .param("recorder", recordedBy)
        .param("reason", correctionReason)
        .param("previous", previousResult)
        .param("id", itemId)
        .update();
  }

  public List<HistoryRow> history(Long itemId) {
    return jdbcClient
        .sql(
            "SELECT id, previous_result, result, previous_actual_visited_at, actual_visited_at,"
                + " correction_reason, effective_at, recorded_at, recorded_by_membership_id FROM"
                + " visit_schedule_item_result_history WHERE schedule_item_id = :item ORDER BY"
                + " recorded_at DESC, id DESC")
        .param("item", itemId)
        .query(
            (rs, rowNum) ->
                new HistoryRow(
                    rs.getLong("id"),
                    rs.getString("previous_result"),
                    rs.getString("result"),
                    instant(rs.getTimestamp("previous_actual_visited_at")),
                    instant(rs.getTimestamp("actual_visited_at")),
                    rs.getString("correction_reason"),
                    instant(rs.getTimestamp("effective_at")),
                    instant(rs.getTimestamp("recorded_at")),
                    rs.getLong("recorded_by_membership_id")))
        .list();
  }

  public int correctionCount(Long itemId) {
    return jdbcClient
        .sql(
            "SELECT count(*) FROM visit_schedule_item_result_history WHERE schedule_item_id ="
                + " :item AND supersedes_history_id IS NOT NULL")
        .param("item", itemId)
        .query(Integer.class)
        .single();
  }

  public List<ClosedRequest> timeClosedOverlapping(Long propertyId, Instant start, Instant end) {
    return jdbcClient
        .sql(
            "SELECT id, applicant_membership_id FROM visit_request WHERE property_id = :property"
                + " AND status = 'TIME_CLOSED' AND requested_start_at < :end AND"
                + " requested_end_at > :start ORDER BY id")
        .param("property", propertyId)
        .param("start", timestamp(start))
        .param("end", timestamp(end))
        .query(
            (rs, rowNum) ->
                new ClosedRequest(rs.getLong("id"), rs.getLong("applicant_membership_id")))
        .list();
  }

  public List<ApprovalScheduleRow> approvalSchedule(Long approvalId) {
    return jdbcClient
        .sql(
            "SELECT si.id, si.coordination_id, si.scheduled_start_at, si.scheduled_end_at,"
                + " si.result, vr.id AS request_id, vr.applicant_office_id,"
                + " vr.applicant_membership_id, vr.property_office_id FROM visit_schedule_item si"
                + " JOIN visit_request vr ON vr.id = si.confirmed_request_id WHERE vr.approval_id ="
                + " :approval AND si.scheduled_start_at IS NOT NULL AND si.scheduled_end_at IS NOT"
                + " NULL ORDER BY si.scheduled_start_at, si.id")
        .param("approval", approvalId)
        .query(
            (rs, rowNum) ->
                new ApprovalScheduleRow(
                    rs.getLong("id"),
                    rs.getLong("coordination_id"),
                    instant(rs.getTimestamp("scheduled_start_at")),
                    instant(rs.getTimestamp("scheduled_end_at")),
                    rs.getString("result"),
                    rs.getLong("request_id"),
                    rs.getLong("applicant_office_id"),
                    rs.getLong("applicant_membership_id"),
                    rs.getLong("property_office_id")))
        .list();
  }

  private static ItemHead head(ResultSet rs) throws SQLException {
    long request = rs.getLong("confirmed_request_id");
    Long requestId = rs.wasNull() ? null : request;
    return new ItemHead(
        rs.getLong("id"),
        rs.getLong("coordination_id"),
        rs.getLong("office_id"),
        rs.getLong("candidate_property_id"),
        rs.getLong("property_id"),
        instant(rs.getTimestamp("scheduled_start_at")),
        instant(rs.getTimestamp("scheduled_end_at")),
        rs.getString("result"),
        instant(rs.getTimestamp("actual_visited_at")),
        instant(rs.getTimestamp("result_recorded_at")),
        requestId);
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static Instant instant(Timestamp value) {
    return value == null ? null : value.toInstant();
  }
}
