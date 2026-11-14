package com.sairo.be.domain.home.repository;

import com.sairo.be.global.support.WithdrawnMemberDisplay;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 홈 업무(task 표)를 JDBC로 다룬다. 사무소 범위는 호출자가 인증된 소속에서 넘긴 office_id로만 정한다. */
@Profile("!migrate")
@Repository
public class TaskRepository {

  public record TaskRow(
      Long id,
      Long officeId,
      String title,
      Long assigneeMembershipId,
      LocalDate dueDate,
      LocalTime dueTime,
      Long relatedCustomerId,
      Long relatedPropertyId,
      String memo,
      String status,
      String sourceType,
      Long sourceId,
      Instant completedAt,
      Instant createdAt,
      Instant updatedAt,
      long version) {}

  public record TaskListRow(
      TaskRow task, String assigneeName, String relatedCustomerName, String relatedPropertyName) {}

  public record TaskValues(
      String title,
      Long assigneeMembershipId,
      LocalDate dueDate,
      LocalTime dueTime,
      Long relatedCustomerId,
      Long relatedPropertyId,
      String memo,
      String status,
      Instant completedAt) {}

  public record ListFilter(
      Long officeId, Long assigneeMembershipId, Set<String> statuses, boolean excludeContract) {}

  private static final String COLUMNS =
      "t.id, t.office_id, t.title, t.assignee_membership_id, t.due_date, t.due_time,"
          + " t.related_customer_id, t.related_property_id, t.memo, t.status, t.source_type,"
          + " t.source_id, t.completed_at, t.created_at, t.updated_at, t.version";

  private final JdbcClient jdbcClient;

  public TaskRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Long insert(
      Long officeId, TaskValues values, String sourceType, Long sourceId, Instant now) {
    return jdbcClient
        .sql(
            "INSERT INTO task (office_id, title, assignee_membership_id, due_date, due_time,"
                + " related_customer_id, related_property_id, memo, status, source_type,"
                + " source_id, completed_at, created_at, updated_at, version) VALUES (:office,"
                + " :title, :assignee, :dueDate, :dueTime, :customer, :property, :memo, :status,"
                + " :sourceType, :sourceId, :completedAt, :now, :now, 1) RETURNING id")
        .param("office", officeId)
        .param("title", values.title())
        .param("assignee", values.assigneeMembershipId())
        .param("dueDate", values.dueDate())
        .param("dueTime", values.dueTime() == null ? null : Time.valueOf(values.dueTime()))
        .param("customer", values.relatedCustomerId())
        .param("property", values.relatedPropertyId())
        .param("memo", values.memo())
        .param("status", values.status())
        .param("sourceType", sourceType)
        .param("sourceId", sourceId)
        .param("completedAt", timestamp(values.completedAt()))
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  public Optional<TaskRow> find(Long officeId, Long taskId) {
    return jdbcClient
        .sql("SELECT " + COLUMNS + " FROM task t WHERE t.id = :id AND t.office_id = :office")
        .param("id", taskId)
        .param("office", officeId)
        .query((rs, rowNum) -> row(rs))
        .optional();
  }

  public Optional<TaskRow> lock(Long officeId, Long taskId) {
    return jdbcClient
        .sql(
            "SELECT "
                + COLUMNS
                + " FROM task t WHERE t.id = :id AND t.office_id = :office FOR UPDATE")
        .param("id", taskId)
        .param("office", officeId)
        .query((rs, rowNum) -> row(rs))
        .optional();
  }

  public Optional<TaskRow> lockBySource(String sourceType, Long sourceId) {
    return jdbcClient
        .sql(
            "SELECT "
                + COLUMNS
                + " FROM task t WHERE t.source_type = :type AND t.source_id = :source ORDER BY"
                + " t.id LIMIT 1 FOR UPDATE")
        .param("type", sourceType)
        .param("source", sourceId)
        .query((rs, rowNum) -> row(rs))
        .optional();
  }

  public void update(Long taskId, TaskValues values, Instant now) {
    jdbcClient
        .sql(
            "UPDATE task SET title = :title, assignee_membership_id = :assignee, due_date ="
                + " :dueDate, due_time = :dueTime, related_customer_id = :customer,"
                + " related_property_id = :property, memo = :memo, status = :status,"
                + " completed_at = :completedAt, updated_at = :now, version = version + 1"
                + " WHERE id = :id")
        .param("title", values.title())
        .param("assignee", values.assigneeMembershipId())
        .param("dueDate", values.dueDate())
        .param("dueTime", values.dueTime() == null ? null : Time.valueOf(values.dueTime()))
        .param("customer", values.relatedCustomerId())
        .param("property", values.relatedPropertyId())
        .param("memo", values.memo())
        .param("status", values.status())
        .param("completedAt", timestamp(values.completedAt()))
        .param("now", Timestamp.from(now))
        .param("id", taskId)
        .update();
  }

  public void delete(Long taskId) {
    jdbcClient.sql("DELETE FROM task WHERE id = :id").param("id", taskId).update();
  }

  public long count(ListFilter filter) {
    return jdbcClient
        .sql("SELECT count(*) FROM task t" + where(filter))
        .params(filterParams(filter))
        .query(Long.class)
        .single();
  }

  public List<TaskListRow> list(ListFilter filter, int limit, long offset) {
    return jdbcClient
        .sql(
            "SELECT "
                + COLUMNS
                + ", u.name AS assignee_name, (u.account_status = 'WITHDRAWN') AS withdrawn,"
                + " m.withdrawn_alias_no, c.name AS customer_name, p.name AS property_name FROM"
                + " task t JOIN office_membership m ON m.id = t.assignee_membership_id JOIN"
                + " app_user u ON u.id = m.user_id LEFT JOIN customer c ON c.id ="
                + " t.related_customer_id LEFT JOIN property p ON p.id = t.related_property_id"
                + where(filter)
                + " ORDER BY t.due_date, t.due_time NULLS LAST, t.id LIMIT :limit OFFSET :offset")
        .params(filterParams(filter))
        .param("limit", limit)
        .param("offset", offset)
        .query(
            (rs, rowNum) ->
                new TaskListRow(
                    row(rs),
                    displayName(rs),
                    rs.getString("customer_name"),
                    rs.getString("property_name")))
        .list();
  }

  /** 승인된 소속인지 확인한다. 업무 담당자는 같은 사무소의 승인된 구성원이어야 한다. */
  public boolean isApprovedMember(Long officeId, Long membershipId) {
    return jdbcClient
        .sql(
            "SELECT EXISTS (SELECT 1 FROM office_membership WHERE id = :id AND office_id ="
                + " :office AND status = 'APPROVED')")
        .param("id", membershipId)
        .param("office", officeId)
        .query(Boolean.class)
        .single();
  }

  public boolean isActiveCustomer(Long officeId, Long customerId) {
    return jdbcClient
        .sql(
            "SELECT EXISTS (SELECT 1 FROM customer WHERE id = :id AND office_id = :office AND"
                + " deleted_at IS NULL)")
        .param("id", customerId)
        .param("office", officeId)
        .query(Boolean.class)
        .single();
  }

  public boolean isActiveProperty(Long officeId, Long propertyId) {
    return jdbcClient
        .sql(
            "SELECT EXISTS (SELECT 1 FROM property WHERE id = :id AND office_id = :office AND"
                + " deleted_at IS NULL)")
        .param("id", propertyId)
        .param("office", officeId)
        .query(Boolean.class)
        .single();
  }

  private static String where(ListFilter filter) {
    StringBuilder where = new StringBuilder(" WHERE t.office_id = :office");
    if (filter.assigneeMembershipId() != null) {
      where.append(" AND t.assignee_membership_id = :assignee");
    }
    if (filter.statuses() != null && !filter.statuses().isEmpty()) {
      where.append(" AND t.status IN (:statuses)");
    }
    if (filter.excludeContract()) {
      where.append(" AND t.source_type NOT IN ('CONTRACT_BALANCE','CONTRACT_MATURITY')");
    }
    return where.toString();
  }

  private static Map<String, Object> filterParams(ListFilter filter) {
    Map<String, Object> params = new HashMap<>();
    params.put("office", filter.officeId());
    if (filter.assigneeMembershipId() != null) {
      params.put("assignee", filter.assigneeMembershipId());
    }
    if (filter.statuses() != null && !filter.statuses().isEmpty()) {
      params.put("statuses", filter.statuses());
    }
    return params;
  }

  static String displayName(ResultSet rs) throws SQLException {
    Integer alias = rs.getObject("withdrawn_alias_no", Integer.class);
    return WithdrawnMemberDisplay.name(
        rs.getBoolean("withdrawn"), rs.getString("assignee_name"), alias);
  }

  private static TaskRow row(ResultSet rs) throws SQLException {
    Time dueTime = rs.getTime("due_time");
    return new TaskRow(
        rs.getLong("id"),
        rs.getLong("office_id"),
        rs.getString("title"),
        rs.getLong("assignee_membership_id"),
        rs.getObject("due_date", LocalDate.class),
        dueTime == null ? null : dueTime.toLocalTime(),
        rs.getObject("related_customer_id", Long.class),
        rs.getObject("related_property_id", Long.class),
        rs.getString("memo"),
        rs.getString("status"),
        rs.getString("source_type"),
        rs.getObject("source_id", Long.class),
        instant(rs, "completed_at"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"),
        rs.getLong("version"));
  }

  static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static Timestamp timestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }
}
