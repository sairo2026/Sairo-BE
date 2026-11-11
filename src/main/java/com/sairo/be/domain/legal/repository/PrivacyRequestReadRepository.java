package com.sairo.be.domain.legal.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class PrivacyRequestReadRepository {

  public record Row(
      Long requestId,
      String requestType,
      String status,
      String identityStatus,
      Instant receivedAt,
      Instant legalDueAt,
      Instant internalDueAt,
      Instant completedAt,
      String decisionReason,
      String responsibleOfficeName,
      String handlerType) {}

  private static final String SELECT =
      """
      SELECT r.id, r.request_type, r.status, r.identity_status, r.received_at, r.legal_due_at,
             r.internal_due_at, r.completed_at, r.decision_reason, o.name AS office_name,
             r.handler_type
      FROM privacy_request r
      LEFT JOIN office o ON o.id = r.office_id
      """;

  private final JdbcClient jdbcClient;

  public PrivacyRequestReadRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public List<Row> page(Long userId, Collection<String> statuses, int limit, long offset) {
    return jdbcClient
        .sql(
            SELECT
                + " WHERE r.requester_user_id = :userId AND r.status IN (:statuses)"
                + " ORDER BY r.received_at DESC, r.id DESC LIMIT :limit OFFSET :offset")
        .param("userId", userId)
        .param("statuses", statuses)
        .param("limit", limit)
        .param("offset", offset)
        .query(PrivacyRequestReadRepository::row)
        .list();
  }

  public long count(Long userId, Collection<String> statuses) {
    Long total =
        jdbcClient
            .sql(
                "SELECT count(*) FROM privacy_request WHERE requester_user_id = :userId"
                    + " AND status IN (:statuses)")
            .param("userId", userId)
            .param("statuses", statuses)
            .query(Long.class)
            .single();
    return total == null ? 0 : total;
  }

  public Optional<Row> find(Long userId, Long requestId) {
    return jdbcClient
        .sql(SELECT + " WHERE r.requester_user_id = :userId AND r.id = :id")
        .param("userId", userId)
        .param("id", requestId)
        .query(PrivacyRequestReadRepository::row)
        .optional();
  }

  public Optional<String> latestSupplementRequest(Long requestId) {
    return jdbcClient
        .sql(
            "SELECT action_detail FROM privacy_request_event WHERE privacy_request_id = :id"
                + " AND event_type = 'REQUEST_SUPPLEMENT' ORDER BY occurred_at DESC, id DESC"
                + " LIMIT 1")
        .param("id", requestId)
        .query(String.class)
        .optional();
  }

  private static Row row(ResultSet rs, int rowNum) throws SQLException {
    return new Row(
        rs.getLong("id"),
        rs.getString("request_type"),
        rs.getString("status"),
        rs.getString("identity_status"),
        instant(rs.getTimestamp("received_at")),
        instant(rs.getTimestamp("legal_due_at")),
        instant(rs.getTimestamp("internal_due_at")),
        instant(rs.getTimestamp("completed_at")),
        rs.getString("decision_reason"),
        rs.getString("office_name"),
        rs.getString("handler_type"));
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
