package com.sairo.be.domain.visit.repository;

import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class VisitRequestReadRepository {

  public record SelectionRow(Long id, int comparedRequestCount, Instant selectedAt) {}

  public record RevalidationRow(
      Long id,
      Long selectionId,
      Long conditionId,
      String result,
      String failureCode,
      String failureDetail,
      Instant checkedAt) {}

  public record OverdueRow(Long id, Long coordinationId, String reason) {}

  private static final String OVERDUE_SELECT =
      """
      SELECT vr.id, vr.coordination_id,
             CASE
               WHEN vr.requested_start_at <= :now THEN 'START_PASSED'
               WHEN NOT EXISTS (SELECT 1 FROM visit_approver_condition c
                                 WHERE c.approval_id = vr.approval_id AND c.status = 'ACTIVE'
                                   AND c.applies_until > :now) THEN 'CONDITION_EXPIRED'
               WHEN g.revoked_at IS NOT NULL THEN 'GRANT_REVOKED'
               ELSE 'GRANT_EXPIRED'
             END AS reason
        FROM visit_request vr
        LEFT JOIN visit_property_apply_grant g ON g.id = vr.access_grant_id
       WHERE vr.status = 'PENDING'
         AND (vr.requested_start_at <= :now
              OR NOT EXISTS (SELECT 1 FROM visit_approver_condition c
                              WHERE c.approval_id = vr.approval_id AND c.status = 'ACTIVE'
                                AND c.applies_until > :now)
              OR (vr.access_grant_id IS NOT NULL
                  AND (g.revoked_at IS NOT NULL OR g.expires_at <= :now)))
      """;

  private final JdbcClient jdbcClient;

  public VisitRequestReadRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public List<SelectionRow> selectionsOf(Long requestId) {
    return jdbcClient
        .sql(
            "SELECT id, compared_request_count, selected_at FROM visit_request_selection"
                + " WHERE request_id = :request ORDER BY selected_at, id")
        .param("request", requestId)
        .query(
            (rs, rowNum) ->
                new SelectionRow(
                    rs.getLong("id"),
                    rs.getInt("compared_request_count"),
                    rs.getTimestamp("selected_at").toInstant()))
        .list();
  }

  public List<RevalidationRow> revalidationsOf(Long requestId) {
    return jdbcClient
        .sql(
            "SELECT id, selection_id, condition_id, result, failure_code, failure_detail,"
                + " checked_at FROM visit_request_revalidation WHERE request_id = :request"
                + " ORDER BY checked_at, id")
        .param("request", requestId)
        .query(
            (rs, rowNum) -> {
              long selection = rs.getLong("selection_id");
              Long selectionId = rs.wasNull() ? null : selection;
              return new RevalidationRow(
                  rs.getLong("id"),
                  selectionId,
                  rs.getLong("condition_id"),
                  rs.getString("result"),
                  rs.getString("failure_code"),
                  rs.getString("failure_detail"),
                  rs.getTimestamp("checked_at").toInstant());
            })
        .list();
  }

  public List<OverdueRow> findOverdue(Instant now, int limit) {
    return jdbcClient
        .sql(OVERDUE_SELECT + " ORDER BY vr.id LIMIT :limit")
        .param("now", java.sql.Timestamp.from(now))
        .param("limit", limit)
        .query(
            (rs, rowNum) ->
                new OverdueRow(
                    rs.getLong("id"), rs.getLong("coordination_id"), rs.getString("reason")))
        .list();
  }

  public OverdueRow findOverdueById(Long requestId, Instant now) {
    return jdbcClient
        .sql(OVERDUE_SELECT + " AND vr.id = :id")
        .param("now", java.sql.Timestamp.from(now))
        .param("id", requestId)
        .query(
            (rs, rowNum) ->
                new OverdueRow(
                    rs.getLong("id"), rs.getLong("coordination_id"), rs.getString("reason")))
        .optional()
        .orElse(null);
  }
}
