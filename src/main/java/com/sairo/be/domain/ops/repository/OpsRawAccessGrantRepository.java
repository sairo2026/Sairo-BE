package com.sairo.be.domain.ops.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 원문 접근 실행 기록(ops_raw_access_grant)이다. */
@Profile("!migrate")
@Repository
public class OpsRawAccessGrantRepository {

  public record Row(
      long id,
      long approvalRequestId,
      String requestType,
      String targetType,
      Long targetId,
      long executedBy,
      Instant executedAt,
      Instant expiresAt,
      String fileObjectKey,
      String fileSha256,
      Instant downloadedAt,
      Instant revokedAt,
      Long revokedBy,
      String revokedByName,
      String revokeReason) {}

  private static final String SELECT =
      "SELECT g.id, g.approval_request_id, g.request_type, g.target_type, g.target_id,"
          + " g.executed_by_operator_id, g.executed_at, g.expires_at, g.file_object_key,"
          + " g.file_sha256, g.downloaded_at, g.revoked_at, g.revoked_by_operator_id,"
          + " o.name AS revoked_by_name, g.revoke_reason FROM ops_raw_access_grant g"
          + " LEFT JOIN operator_account o ON o.id = g.revoked_by_operator_id";

  private final JdbcClient jdbcClient;

  public OpsRawAccessGrantRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public long insert(
      long approvalRequestId,
      String requestType,
      String targetType,
      Long targetId,
      long executedBy,
      Instant executedAt,
      Instant expiresAt,
      String fileObjectKey,
      String fileSha256) {
    return jdbcClient
        .sql(
            "INSERT INTO ops_raw_access_grant (approval_request_id, request_type, target_type,"
                + " target_id, executed_by_operator_id, executed_at, expires_at, file_object_key,"
                + " file_sha256) VALUES (:request, :type, :targetType, :targetId, :executedBy,"
                + " :executedAt, :expiresAt, :key, :sha) RETURNING id")
        .param("request", approvalRequestId)
        .param("type", requestType)
        .param("targetType", targetType)
        .param("targetId", targetId)
        .param("executedBy", executedBy)
        .param("executedAt", Timestamp.from(executedAt))
        .param("expiresAt", Timestamp.from(expiresAt))
        .param("key", fileObjectKey)
        .param("sha", fileSha256)
        .query(Long.class)
        .single();
  }

  public Optional<Row> find(long id) {
    return jdbcClient.sql(SELECT + " WHERE g.id = :id").param("id", id).query(this::map).optional();
  }

  public Optional<Row> findByRequest(long requestId) {
    return jdbcClient
        .sql(SELECT + " WHERE g.approval_request_id = :id")
        .param("id", requestId)
        .query(this::map)
        .optional();
  }

  public Optional<Row> lock(long id) {
    jdbcClient
        .sql("SELECT id FROM ops_raw_access_grant WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(Long.class)
        .optional();
    return find(id);
  }

  public void markDownloaded(long id, Instant now) {
    jdbcClient
        .sql("UPDATE ops_raw_access_grant SET downloaded_at = :now WHERE id = :id")
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  public void revoke(long id, long operatorId, String reason, Instant now) {
    jdbcClient
        .sql(
            "UPDATE ops_raw_access_grant SET revoked_at = :now, revoked_by_operator_id ="
                + " :operator, revoke_reason = :reason WHERE id = :id")
        .param("now", Timestamp.from(now))
        .param("operator", operatorId)
        .param("reason", reason)
        .param("id", id)
        .update();
  }

  private Row map(ResultSet rs, int rowNumber) throws SQLException {
    return new Row(
        rs.getLong("id"),
        rs.getLong("approval_request_id"),
        rs.getString("request_type"),
        rs.getString("target_type"),
        rs.getObject("target_id", Long.class),
        rs.getLong("executed_by_operator_id"),
        rs.getTimestamp("executed_at").toInstant(),
        rs.getTimestamp("expires_at").toInstant(),
        rs.getString("file_object_key"),
        rs.getString("file_sha256"),
        OpsApprovalRequestRepository.instant(rs.getTimestamp("downloaded_at")),
        OpsApprovalRequestRepository.instant(rs.getTimestamp("revoked_at")),
        rs.getObject("revoked_by_operator_id", Long.class),
        rs.getString("revoked_by_name"),
        rs.getString("revoke_reason"));
  }
}
