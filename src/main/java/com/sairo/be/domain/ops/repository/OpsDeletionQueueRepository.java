package com.sairo.be.domain.ops.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 파기 큐 조회와 상태 기록이다(OPS-13). */
@Profile("!migrate")
@Repository
public class OpsDeletionQueueRepository {

  public record Row(
      long id,
      String targetType,
      long targetId,
      Long officeId,
      String reason,
      Instant scheduledAt,
      Instant executedAt,
      Long executedByOperatorId,
      Instant cancelledAt,
      Long cancelledByOperatorId,
      String cancelReason,
      long targetCount,
      long excludedCount,
      String result,
      String exclusionReason,
      Long holdApprovedByOperatorId,
      Instant holdExpiresAt,
      Instant exclusionReviewedAt,
      String storageResult,
      String searchIndexResult,
      String fileResult,
      String temporaryExportResult,
      Instant backupExpiresAt,
      int retryCount,
      Long verifiedByOperatorId,
      Instant verifiedAt,
      String verificationEvidence,
      Instant createdAt) {}

  public record Filter(Long queueId, String state, String targetType, Long officeId) {}

  public record StorageResults(
      String storage, String searchIndex, String file, String temporaryExport) {}

  private static final String COLUMNS =
      "SELECT id, target_type, target_id, office_id, reason, scheduled_at, executed_at,"
          + " executed_by_operator_id, cancelled_at, cancelled_by_operator_id, cancel_reason,"
          + " target_count, excluded_count, result, exclusion_reason,"
          + " hold_approved_by_operator_id, hold_expires_at, exclusion_reviewed_at,"
          + " storage_result, search_index_result, file_result, temporary_export_result,"
          + " backup_expires_at, retry_count, verified_by_operator_id, verified_at,"
          + " verification_evidence, created_at FROM deletion_queue";

  private final JdbcClient jdbcClient;

  public OpsDeletionQueueRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** 끝나지 않은 건을 파기 예정 시각 순으로 먼저 보이고 끝난 건은 뒤에 둔다. */
  public List<Row> list(Filter filter, int limit, long offset) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = where(filter, params);
    var spec =
        jdbcClient.sql(
            COLUMNS
                + where
                + " ORDER BY CASE WHEN cancelled_at IS NOT NULL OR result IS NOT NULL THEN 1"
                + " ELSE 0 END, scheduled_at, id LIMIT :limit OFFSET :offset");
    for (var entry : params.entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    return spec.param("limit", limit)
        .param("offset", offset)
        .query(OpsDeletionQueueRepository::map)
        .list();
  }

  public long count(Filter filter) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = where(filter, params);
    var spec = jdbcClient.sql("SELECT count(*) FROM deletion_queue" + where);
    for (var entry : params.entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    return spec.query(Long.class).single();
  }

  public Optional<Row> find(long queueId) {
    return jdbcClient
        .sql(COLUMNS + " WHERE id = :id")
        .param("id", queueId)
        .query(OpsDeletionQueueRepository::map)
        .optional();
  }

  public Optional<Row> lock(long queueId) {
    return jdbcClient
        .sql(COLUMNS + " WHERE id = :id FOR UPDATE")
        .param("id", queueId)
        .query(OpsDeletionQueueRepository::map)
        .optional();
  }

  public void updateCounts(long queueId, long targetCount, long excludedCount) {
    jdbcClient
        .sql(
            "UPDATE deletion_queue SET target_count = :target, excluded_count = :excluded WHERE"
                + " id = :id")
        .param("target", targetCount)
        .param("excluded", excludedCount)
        .param("id", queueId)
        .update();
  }

  public void exclude(
      long queueId,
      String reason,
      long approvedBy,
      Instant holdExpiresAt,
      Instant reviewAt,
      long targetCount) {
    jdbcClient
        .sql(
            "UPDATE deletion_queue SET result = 'EXCLUDED', exclusion_reason = :reason,"
                + " hold_approved_by_operator_id = :approvedBy, hold_expires_at = :holdExpiresAt,"
                + " exclusion_reviewed_at = :reviewAt, target_count = :target, excluded_count ="
                + " :target, storage_result = 'EXCLUDED', search_index_result = 'EXCLUDED',"
                + " file_result = 'EXCLUDED', temporary_export_result = 'EXCLUDED' WHERE id = :id")
        .param("reason", reason)
        .param("approvedBy", approvedBy)
        .param("holdExpiresAt", Timestamp.from(holdExpiresAt))
        .param("reviewAt", Timestamp.from(reviewAt))
        .param("target", targetCount)
        .param("id", queueId)
        .update();
  }

  public void executed(
      long queueId, long operatorId, Instant now, StorageResults results, int retryCount) {
    jdbcClient
        .sql(
            "UPDATE deletion_queue SET executed_at = :now, executed_by_operator_id = :operator,"
                + " result = NULL, storage_result = :storage, search_index_result = :search,"
                + " file_result = :file, temporary_export_result = :export, retry_count = :retry,"
                + " verified_by_operator_id = NULL, verified_at = NULL, verification_evidence ="
                + " NULL WHERE id = :id")
        .param("now", Timestamp.from(now))
        .param("operator", operatorId)
        .param("storage", results.storage())
        .param("search", results.searchIndex())
        .param("file", results.file())
        .param("export", results.temporaryExport())
        .param("retry", retryCount)
        .param("id", queueId)
        .update();
  }

  public void verified(
      long queueId,
      String result,
      StorageResults results,
      Instant backupExpiresAt,
      Long verifiedBy,
      Instant verifiedAt,
      String evidence) {
    jdbcClient
        .sql(
            "UPDATE deletion_queue SET result = :result, storage_result = :storage,"
                + " search_index_result = :search, file_result = :file, temporary_export_result ="
                + " :export, backup_expires_at = :backup, verified_by_operator_id = :verifiedBy,"
                + " verified_at = :verifiedAt, verification_evidence = :evidence WHERE id = :id")
        .param("result", result)
        .param("storage", results.storage())
        .param("search", results.searchIndex())
        .param("file", results.file())
        .param("export", results.temporaryExport())
        .param("backup", backupExpiresAt == null ? null : Timestamp.from(backupExpiresAt))
        .param("verifiedBy", verifiedBy)
        .param("verifiedAt", verifiedAt == null ? null : Timestamp.from(verifiedAt))
        .param("evidence", evidence)
        .param("id", queueId)
        .update();
  }

  public void cancel(long queueId, long operatorId, String reason, Instant now) {
    jdbcClient
        .sql(
            "UPDATE deletion_queue SET cancelled_at = :now, cancelled_by_operator_id = :operator,"
                + " cancel_reason = :reason WHERE id = :id")
        .param("now", Timestamp.from(now))
        .param("operator", operatorId)
        .param("reason", reason)
        .param("id", queueId)
        .update();
  }

  private static String where(Filter filter, Map<String, Object> params) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    if (filter.queueId() != null) {
      where.append(" AND id = :queueId");
      params.put("queueId", filter.queueId());
    }
    if (filter.targetType() != null) {
      where.append(" AND target_type = :targetType");
      params.put("targetType", filter.targetType());
    }
    if (filter.officeId() != null) {
      where.append(" AND office_id = :officeId");
      params.put("officeId", filter.officeId());
    }
    if (filter.state() != null) {
      where.append(
          switch (filter.state()) {
            case "PENDING" ->
                " AND cancelled_at IS NULL AND executed_at IS NULL AND result IS NULL";
            case "AWAITING_VERIFICATION" ->
                " AND cancelled_at IS NULL AND executed_at IS NOT NULL AND result IS NULL";
            case "CANCELLED" -> " AND cancelled_at IS NOT NULL";
            default -> " AND result = :result";
          });
      if (!List.of("PENDING", "AWAITING_VERIFICATION", "CANCELLED").contains(filter.state())) {
        params.put("result", filter.state());
      }
    }
    return where.toString();
  }

  private static Row map(ResultSet rs, int rowNum) throws SQLException {
    return new Row(
        rs.getLong("id"),
        rs.getString("target_type"),
        rs.getLong("target_id"),
        rs.getObject("office_id", Long.class),
        rs.getString("reason"),
        instant(rs, "scheduled_at"),
        instant(rs, "executed_at"),
        rs.getObject("executed_by_operator_id", Long.class),
        instant(rs, "cancelled_at"),
        rs.getObject("cancelled_by_operator_id", Long.class),
        rs.getString("cancel_reason"),
        rs.getLong("target_count"),
        rs.getLong("excluded_count"),
        rs.getString("result"),
        rs.getString("exclusion_reason"),
        rs.getObject("hold_approved_by_operator_id", Long.class),
        instant(rs, "hold_expires_at"),
        instant(rs, "exclusion_reviewed_at"),
        rs.getString("storage_result"),
        rs.getString("search_index_result"),
        rs.getString("file_result"),
        rs.getString("temporary_export_result"),
        instant(rs, "backup_expires_at"),
        rs.getInt("retry_count"),
        rs.getObject("verified_by_operator_id", Long.class),
        instant(rs, "verified_at"),
        rs.getString("verification_evidence"),
        instant(rs, "created_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
