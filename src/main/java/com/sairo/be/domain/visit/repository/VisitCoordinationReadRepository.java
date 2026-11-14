package com.sairo.be.domain.visit.repository;

import com.sairo.be.domain.visit.entity.CoordinationStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class VisitCoordinationReadRepository {

  public record ListRow(
      long id,
      long customerId,
      long managerMembershipId,
      CoordinationStatus status,
      LocalDate periodStartDate,
      LocalDate periodEndDate,
      Instant createdAt,
      Instant updatedAt,
      long version) {}

  private final JdbcClient jdbcClient;

  public VisitCoordinationReadRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public List<ListRow> findPage(
      long officeId, Long managerScope, List<CoordinationStatus> statuses, int limit, long offset) {
    Map<String, Object> params = new HashMap<>();
    String where = where(officeId, managerScope, statuses, params);
    params.put("limit", limit);
    params.put("offset", offset);
    return jdbcClient
        .sql(
            "SELECT id, customer_id, manager_membership_id, status, period_start_date,"
                + " period_end_date, created_at, updated_at, version FROM visit_coordination"
                + where
                + " ORDER BY updated_at DESC, id DESC LIMIT :limit OFFSET :offset")
        .params(params)
        .query(
            (rs, rowNum) ->
                new ListRow(
                    rs.getLong("id"),
                    rs.getLong("customer_id"),
                    rs.getLong("manager_membership_id"),
                    CoordinationStatus.valueOf(rs.getString("status")),
                    rs.getObject("period_start_date", LocalDate.class),
                    rs.getObject("period_end_date", LocalDate.class),
                    toInstant(rs.getTimestamp("created_at")),
                    toInstant(rs.getTimestamp("updated_at")),
                    rs.getLong("version")))
        .list();
  }

  public long count(long officeId, Long managerScope, List<CoordinationStatus> statuses) {
    Map<String, Object> params = new HashMap<>();
    String where = where(officeId, managerScope, statuses, params);
    return jdbcClient
        .sql("SELECT count(*) FROM visit_coordination" + where)
        .params(params)
        .query(Long.class)
        .single();
  }

  public Map<CoordinationStatus, Long> statusCounts(long officeId, Long managerScope) {
    Map<CoordinationStatus, Long> counts = new EnumMap<>(CoordinationStatus.class);
    for (CoordinationStatus status : CoordinationStatus.values()) {
      counts.put(status, 0L);
    }
    Map<String, Object> params = new HashMap<>();
    String where = where(officeId, managerScope, List.of(), params);
    jdbcClient
        .sql(
            "SELECT status, count(*) AS total FROM visit_coordination" + where + " GROUP BY status")
        .params(params)
        .query(
            (rs, rowNum) -> {
              counts.put(CoordinationStatus.valueOf(rs.getString("status")), rs.getLong("total"));
              return null;
            })
        .list();
    return counts;
  }

  private static String where(
      long officeId,
      Long managerScope,
      List<CoordinationStatus> statuses,
      Map<String, Object> params) {
    StringBuilder where = new StringBuilder(" WHERE office_id = :officeId");
    params.put("officeId", officeId);
    if (managerScope != null) {
      where.append(" AND manager_membership_id = :managerScope");
      params.put("managerScope", managerScope);
    }
    if (!statuses.isEmpty()) {
      where.append(" AND status IN (:statuses)");
      params.put("statuses", statuses.stream().map(CoordinationStatus::name).toList());
    }
    return where.toString();
  }

  private static Instant toInstant(Timestamp timestamp) {
    return timestamp.toInstant();
  }
}
