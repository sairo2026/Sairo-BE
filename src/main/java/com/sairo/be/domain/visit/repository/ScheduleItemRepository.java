package com.sairo.be.domain.visit.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class ScheduleItemRepository {

  public record ItemRow(
      Long id,
      Long candidatePropertyId,
      Instant startAt,
      Instant endAt,
      String result,
      Instant resultRecordedAt,
      Instant travelStartsAt,
      Instant travelEndsAt,
      Long confirmedRequestId,
      int sortOrder,
      int correctionCount,
      Instant createdAt) {

    public boolean kept() {
      return !"CANCELLED".equals(result);
    }
  }

  private final JdbcClient jdbcClient;

  public ScheduleItemRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Long insertConfirmed(
      Long coordinationId,
      Long officeId,
      Long candidateId,
      Instant startAt,
      Instant endAt,
      int sortOrder,
      Long requestId,
      Instant travelStartsAt,
      Instant travelEndsAt) {
    return jdbcClient
        .sql(
            "INSERT INTO visit_schedule_item (coordination_id, office_id, candidate_property_id,"
                + " scheduled_start_at, scheduled_end_at, sort_order, result,"
                + " confirmed_request_id, travel_starts_at, travel_ends_at) VALUES (:coordination,"
                + " :office, :candidate, :start, :end, :sort, 'SCHEDULED', :request,"
                + " :travelStart, :travelEnd) RETURNING id")
        .param("coordination", coordinationId)
        .param("office", officeId)
        .param("candidate", candidateId)
        .param("start", Timestamp.from(startAt))
        .param("end", Timestamp.from(endAt))
        .param("sort", sortOrder)
        .param("request", requestId)
        .param("travelStart", travelStartsAt == null ? null : Timestamp.from(travelStartsAt))
        .param("travelEnd", travelEndsAt == null ? null : Timestamp.from(travelEndsAt))
        .query(Long.class)
        .single();
  }

  public List<ItemRow> listByCoordination(Long coordinationId) {
    return jdbcClient
        .sql(
            "SELECT si.id, si.candidate_property_id, si.scheduled_start_at,"
                + " si.scheduled_end_at, si.result, si.result_recorded_at, si.travel_starts_at,"
                + " si.travel_ends_at, si.confirmed_request_id, si.sort_order, (SELECT count(*)"
                + " FROM visit_schedule_item_result_history h WHERE h.schedule_item_id = si.id"
                + " AND h.supersedes_history_id IS NOT NULL) AS corrections, si.created_at FROM"
                + " visit_schedule_item si WHERE si.coordination_id = :coordination ORDER BY"
                + " si.scheduled_start_at NULLS LAST, si.id")
        .param("coordination", coordinationId)
        .query(
            (rs, rowNum) ->
                new ItemRow(
                    rs.getLong("id"),
                    rs.getLong("candidate_property_id"),
                    instant(rs.getTimestamp("scheduled_start_at")),
                    instant(rs.getTimestamp("scheduled_end_at")),
                    rs.getString("result"),
                    instant(rs.getTimestamp("result_recorded_at")),
                    instant(rs.getTimestamp("travel_starts_at")),
                    instant(rs.getTimestamp("travel_ends_at")),
                    nullable(rs.getLong("confirmed_request_id"), rs),
                    rs.getInt("sort_order"),
                    rs.getInt("corrections"),
                    instant(rs.getTimestamp("created_at"))))
        .list();
  }

  public long confirmedCandidateCount(Long coordinationId) {
    return jdbcClient
        .sql(
            "SELECT count(DISTINCT candidate_property_id) FROM visit_schedule_item WHERE"
                + " coordination_id = :coordination AND result IN ('SCHEDULED', 'COMPLETED',"
                + " 'NO_SHOW')")
        .param("coordination", coordinationId)
        .query(Long.class)
        .single();
  }

  public long scheduleVersion(Long coordinationId) {
    return jdbcClient
        .sql(
            "SELECT (SELECT count(*) FROM visit_request_status_history h JOIN visit_request r ON"
                + " r.id = h.request_id WHERE r.coordination_id = :coordination AND h.to_status ="
                + " 'CONFIRMED') + (SELECT count(*) FROM visit_coordination_status_history c"
                + " WHERE c.coordination_id = :coordination AND c.to_status IN"
                + " ('RESCHEDULE_REQUIRED', 'CANCELLED')) + (SELECT count(*) FROM"
                + " visit_schedule_item_result_history h JOIN visit_schedule_item si ON si.id ="
                + " h.schedule_item_id WHERE si.coordination_id = :coordination AND h.result ="
                + " 'CANCELLED' AND h.previous_result = 'SCHEDULED' AND h.cancel_reason_code IS"
                + " DISTINCT FROM 'CHANGE_REPLACED')")
        .param("coordination", coordinationId)
        .query(Long.class)
        .single();
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static Long nullable(long value, java.sql.ResultSet rs) throws java.sql.SQLException {
    return rs.wasNull() ? null : value;
  }
}
