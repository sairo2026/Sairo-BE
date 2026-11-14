package com.sairo.be.domain.home.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 홈 대시보드 집계를 JDBC로 읽는다. manager가 있으면 중개보조원 범위(본인이 담당인 조율·업무)로 좁힌다. 계약 표는 읽기만 하며 계약 지표의 생성 규칙은 계약
 * 단계가 맡는다.
 */
@Profile("!migrate")
@Repository
public class HomeSummaryRepository {

  public record VisitRow(
      Long itemId,
      Long coordinationId,
      Instant startAt,
      String propertyAddress,
      String propertyName,
      String customerName) {}

  public record FollowupRow(Long id, LocalDate dueDate) {}

  public record OpenTaskRow(
      Long id,
      String title,
      LocalDate dueDate,
      String status,
      Long assigneeId,
      String assigneeName) {}

  public record DayCount(LocalDate date, long count) {}

  private final JdbcClient jdbcClient;

  public HomeSummaryRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public List<VisitRow> scheduledVisits(Long officeId, Long manager, Instant from, Instant to) {
    return jdbcClient
        .sql(
            "SELECT si.id, si.coordination_id, si.scheduled_start_at, p.address, p.name AS"
                + " property_name, c.name AS customer_name FROM visit_schedule_item si JOIN"
                + " visit_coordination vc ON vc.id = si.coordination_id JOIN"
                + " visit_candidate_property vcp ON vcp.id = si.candidate_property_id JOIN property"
                + " p ON p.id = vcp.property_id JOIN customer c ON c.id = vc.customer_id WHERE"
                + " si.office_id = :office AND si.result = 'SCHEDULED' AND vc.status <>"
                + " 'CANCELLED' AND si.scheduled_start_at >= :from AND si.scheduled_start_at <"
                + " :to"
                + managerClause(manager)
                + " ORDER BY si.scheduled_start_at, si.id")
        .params(scope(officeId, manager))
        .param("from", Timestamp.from(from))
        .param("to", Timestamp.from(to))
        .query(
            (rs, rowNum) ->
                new VisitRow(
                    rs.getLong("id"),
                    rs.getLong("coordination_id"),
                    TaskRepository.instant(rs, "scheduled_start_at"),
                    rs.getString("address"),
                    rs.getString("property_name"),
                    rs.getString("customer_name")))
        .list();
  }

  public List<FollowupRow> openFollowups(Long officeId) {
    return jdbcClient
        .sql(
            "SELECT f.id, COALESCE(f.report_deadline, c.lease_end_date, (f.created_at AT TIME"
                + " ZONE 'Asia/Seoul')::date) AS due_date FROM contract_followup_task f JOIN"
                + " contract c ON c.id = f.contract_id WHERE c.office_id = :office AND"
                + " f.submitted = false AND c.contract_status = 'SIGNED' AND"
                + " c.duplicate_excluded_at IS NULL ORDER BY due_date, f.id")
        .param("office", officeId)
        .query(
            (rs, rowNum) ->
                new FollowupRow(rs.getLong("id"), rs.getObject("due_date", LocalDate.class)))
        .list();
  }

  public long activeCoordinations(Long officeId, Long manager) {
    return jdbcClient
        .sql(
            "SELECT count(*) FROM visit_coordination vc WHERE vc.office_id = :office AND"
                + " vc.status NOT IN ('VISIT_COMPLETED','VISIT_INCOMPLETE','CANCELLED')"
                + managerClause(manager))
        .params(scope(officeId, manager))
        .query(Long.class)
        .single();
  }

  public Map<String, Long> coordinationStatusCounts(Long officeId, Long manager) {
    Map<String, Long> counts = new HashMap<>();
    jdbcClient
        .sql(
            "SELECT vc.status, count(*) AS cnt FROM visit_coordination vc WHERE vc.office_id ="
                + " :office"
                + managerClause(manager)
                + " GROUP BY vc.status")
        .params(scope(officeId, manager))
        .query(
            (rs, rowNum) -> {
              counts.put(rs.getString("status"), rs.getLong("cnt"));
              return null;
            })
        .list();
    return counts;
  }

  public List<OpenTaskRow> openTasks(
      Long officeId, Long assignee, LocalDate until, boolean excludeContract, int limit) {
    return jdbcClient
        .sql(
            "SELECT t.id, t.title, t.due_date, t.status, t.assignee_membership_id, u.name AS"
                + " assignee_name, (u.account_status = 'WITHDRAWN') AS withdrawn,"
                + " m.withdrawn_alias_no FROM task t JOIN office_membership m ON m.id ="
                + " t.assignee_membership_id JOIN app_user u ON u.id = m.user_id WHERE"
                + " t.office_id = :office AND t.status = 'TODO' AND t.due_date <= :until"
                + taskScope(assignee, excludeContract)
                + " ORDER BY t.due_date, t.due_time NULLS LAST, t.id LIMIT :limit")
        .params(taskParams(officeId, assignee))
        .param("until", until)
        .param("limit", limit)
        .query(
            (rs, rowNum) ->
                new OpenTaskRow(
                    rs.getLong("id"),
                    rs.getString("title"),
                    rs.getObject("due_date", LocalDate.class),
                    rs.getString("status"),
                    rs.getLong("assignee_membership_id"),
                    TaskRepository.displayName(rs)))
        .list();
  }

  public long leaseExpiringWithin(Long officeId, LocalDate from, LocalDate until) {
    return jdbcClient
        .sql(
            "SELECT count(*) FROM contract c WHERE c.office_id = :office AND c.contract_status"
                + " = 'SIGNED' AND c.deal_type IN ('JEONSE','MONTHLY') AND"
                + " c.duplicate_excluded_at IS NULL AND c.lease_end_date BETWEEN :from AND"
                + " :until")
        .param("office", officeId)
        .param("from", from)
        .param("until", until)
        .query(Long.class)
        .single();
  }

  /** 기간 안 확정 방문(취소 제외)을 Asia/Seoul 날짜별로 센다. */
  public List<DayCount> visitDays(Long officeId, Long manager, Instant from, Instant to) {
    return jdbcClient
        .sql(
            "SELECT (si.scheduled_start_at AT TIME ZONE 'Asia/Seoul')::date AS day, count(*) AS"
                + " cnt FROM visit_schedule_item si JOIN visit_coordination vc ON vc.id ="
                + " si.coordination_id WHERE si.office_id = :office AND si.result <> 'CANCELLED'"
                + " AND si.scheduled_start_at >= :from AND si.scheduled_start_at < :to"
                + managerClause(manager)
                + " GROUP BY day")
        .params(scope(officeId, manager))
        .param("from", Timestamp.from(from))
        .param("to", Timestamp.from(to))
        .query(
            (rs, rowNum) -> new DayCount(rs.getObject("day", LocalDate.class), rs.getLong("cnt")))
        .list();
  }

  /** 기간 안 미완료 업무를 예정일별로 센다. */
  public List<DayCount> taskDays(
      Long officeId, Long assignee, LocalDate from, LocalDate to, boolean excludeContract) {
    return jdbcClient
        .sql(
            "SELECT t.due_date AS day, count(*) AS cnt FROM task t WHERE t.office_id = :office"
                + " AND t.status = 'TODO' AND t.due_date BETWEEN :from AND :to"
                + taskScope(assignee, excludeContract)
                + " GROUP BY t.due_date")
        .params(taskParams(officeId, assignee))
        .param("from", from)
        .param("to", to)
        .query(
            (rs, rowNum) -> new DayCount(rs.getObject("day", LocalDate.class), rs.getLong("cnt")))
        .list();
  }

  public record RequestCounts(long pending, long revalidationFailed, long timeClosed) {}

  public RequestCounts requestCounts(Long officeId, Long manager) {
    return jdbcClient
        .sql(
            "SELECT count(*) FILTER (WHERE r.status = 'PENDING') AS pending, count(*) FILTER"
                + " (WHERE r.status = 'REVALIDATION_FAILED') AS failed, count(*) FILTER (WHERE"
                + " r.status = 'TIME_CLOSED') AS closed FROM visit_request r JOIN"
                + " visit_coordination vc ON vc.id = r.coordination_id AND vc.office_id ="
                + " r.applicant_office_id WHERE vc.office_id = :office AND vc.status NOT IN"
                + " ('VISIT_COMPLETED','VISIT_INCOMPLETE','CANCELLED')"
                + managerClause(manager))
        .params(scope(officeId, manager))
        .query(
            (rs, rowNum) ->
                new RequestCounts(
                    rs.getLong("pending"), rs.getLong("failed"), rs.getLong("closed")))
        .single();
  }

  public long unreadVisitRequestNotices(Long membershipId) {
    return jdbcClient
        .sql(
            "SELECT count(*) FROM visit_request_notice WHERE recipient_membership_id ="
                + " :member AND read_at IS NULL")
        .param("member", membershipId)
        .query(Long.class)
        .single();
  }

  private static String managerClause(Long manager) {
    return manager == null ? "" : " AND vc.manager_membership_id = :manager";
  }

  private static Map<String, Object> scope(Long officeId, Long manager) {
    Map<String, Object> params = new HashMap<>();
    params.put("office", officeId);
    if (manager != null) {
      params.put("manager", manager);
    }
    return params;
  }

  private static String taskScope(Long assignee, boolean excludeContract) {
    String clause = assignee == null ? "" : " AND t.assignee_membership_id = :assignee";
    if (excludeContract) {
      clause += " AND t.source_type NOT IN ('CONTRACT_BALANCE','CONTRACT_MATURITY')";
    }
    return clause;
  }

  private static Map<String, Object> taskParams(Long officeId, Long assignee) {
    Map<String, Object> params = new HashMap<>();
    params.put("office", officeId);
    if (assignee != null) {
      params.put("assignee", assignee);
    }
    return params;
  }
}
