package com.sairo.be.domain.visit.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 매물을 가진 사무소가 자기 매물에 들어온 다른 사무소 신청과 확정 방문을 읽는다(VISIT-22). */
@Profile("!migrate")
@Repository
public class CrossOfficeVisitRepository {

  public record RequestRow(
      Long id,
      String requestKind,
      String status,
      Long applicantOfficeId,
      Long applicantMembershipId,
      Instant requestedStartAt,
      Instant requestedEndAt,
      int expectedDurationMinutes,
      Instant submittedAt,
      Instant closedAt,
      String closeReason) {}

  public record ScheduleRow(
      Long itemId,
      Long coordinationId,
      Instant startAt,
      Instant endAt,
      String result,
      Long applicantOfficeId,
      Long applicantMembershipId,
      Long confirmedRequestId) {}

  private static final String REQUEST_FROM =
      " FROM visit_request vr WHERE vr.property_id = :property AND vr.property_office_id ="
          + " :office AND vr.applicant_office_id <> vr.property_office_id AND vr.status <>"
          + " 'SELECTED'";

  private final JdbcClient jdbcClient;

  public CrossOfficeVisitRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public long countRequests(Long propertyId, Long officeId, Set<String> statuses) {
    return jdbcClient
        .sql("SELECT count(*)" + REQUEST_FROM + statusClause(statuses))
        .param("property", propertyId)
        .param("office", officeId)
        .param("statuses", statuses)
        .query(Long.class)
        .single();
  }

  public List<RequestRow> requests(
      Long propertyId, Long officeId, Set<String> statuses, int limit, long offset) {
    return jdbcClient
        .sql(
            "SELECT vr.id, vr.request_kind, vr.status, vr.applicant_office_id,"
                + " vr.applicant_membership_id, vr.requested_start_at, vr.requested_end_at,"
                + " vr.expected_duration_minutes, vr.submitted_at, vr.closed_at, (SELECT h.reason"
                + " FROM visit_request_status_history h WHERE h.request_id = vr.id AND"
                + " h.to_status = 'EXPIRED' AND h.source = 'SYSTEM' ORDER BY h.id DESC LIMIT 1)"
                + " AS close_reason"
                + REQUEST_FROM
                + statusClause(statuses)
                + " ORDER BY vr.submitted_at DESC, vr.id DESC LIMIT :limit OFFSET :offset")
        .param("property", propertyId)
        .param("office", officeId)
        .param("statuses", statuses)
        .param("limit", limit)
        .param("offset", offset)
        .query(
            (rs, rowNum) ->
                new RequestRow(
                    rs.getLong("id"),
                    rs.getString("request_kind"),
                    rs.getString("status"),
                    rs.getLong("applicant_office_id"),
                    rs.getLong("applicant_membership_id"),
                    ApplyGrantRepository.instant(rs, "requested_start_at"),
                    ApplyGrantRepository.instant(rs, "requested_end_at"),
                    rs.getInt("expected_duration_minutes"),
                    ApplyGrantRepository.instant(rs, "submitted_at"),
                    ApplyGrantRepository.instant(rs, "closed_at"),
                    rs.getString("close_reason")))
        .list();
  }

  public long countSchedules(
      Long propertyId, Long officeId, Instant from, Instant to, String result) {
    return jdbcClient
        .sql("SELECT count(*)" + scheduleFrom(from, to, result))
        .param("property", propertyId)
        .param("office", officeId)
        .param("from", from == null ? null : Timestamp.from(from))
        .param("to", to == null ? null : Timestamp.from(to))
        .param("result", result)
        .query(Long.class)
        .single();
  }

  public List<ScheduleRow> schedules(
      Long propertyId,
      Long officeId,
      Instant from,
      Instant to,
      String result,
      int limit,
      long offset) {
    return jdbcClient
        .sql(
            "SELECT i.id, i.coordination_id, i.scheduled_start_at, i.scheduled_end_at, i.result,"
                + " vr.applicant_office_id, vr.applicant_membership_id, vr.id AS request_id"
                + scheduleFrom(from, to, result)
                + " ORDER BY i.scheduled_start_at, i.id LIMIT :limit OFFSET :offset")
        .param("property", propertyId)
        .param("office", officeId)
        .param("from", from == null ? null : Timestamp.from(from))
        .param("to", to == null ? null : Timestamp.from(to))
        .param("result", result)
        .param("limit", limit)
        .param("offset", offset)
        .query(
            (rs, rowNum) ->
                new ScheduleRow(
                    rs.getLong("id"),
                    rs.getLong("coordination_id"),
                    ApplyGrantRepository.instant(rs, "scheduled_start_at"),
                    ApplyGrantRepository.instant(rs, "scheduled_end_at"),
                    rs.getString("result"),
                    rs.getLong("applicant_office_id"),
                    rs.getLong("applicant_membership_id"),
                    rs.getLong("request_id")))
        .list();
  }

  private static String statusClause(Set<String> statuses) {
    return statuses == null || statuses.isEmpty() ? "" : " AND vr.status IN (:statuses)";
  }

  private static String scheduleFrom(Instant from, Instant to, String result) {
    List<String> where = new ArrayList<>();
    where.add("vr.property_id = :property");
    where.add("vr.property_office_id = :office");
    where.add("vr.applicant_office_id <> vr.property_office_id");
    if (from != null) {
      where.add("i.scheduled_start_at >= :from");
    }
    if (to != null) {
      where.add("i.scheduled_start_at < :to");
    }
    if (result != null) {
      where.add("i.result = :result");
    }
    return " FROM visit_schedule_item i JOIN visit_request vr ON vr.id = i.confirmed_request_id"
        + " WHERE "
        + String.join(" AND ", where);
  }
}
