package com.sairo.be.domain.migration.repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class ImportSupportLinkRepository {

  public record ActiveRequest(long requestId, String status) {}

  private final JdbcClient jdbcClient;

  public ImportSupportLinkRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Optional<ActiveRequest> findActive(long batchId) {
    return jdbcClient
        .sql(
            "SELECT id, status FROM import_support_request WHERE import_batch_id = :batchId"
                + " AND status NOT IN ('CANCELLED','REJECTED')")
        .param("batchId", batchId)
        .query((rs, rowNum) -> new ActiveRequest(rs.getLong(1), rs.getString(2)))
        .optional();
  }

  public Map<Long, ActiveRequest> findActiveByBatchIds(Collection<Long> batchIds) {
    Map<Long, ActiveRequest> result = new HashMap<>();
    if (batchIds.isEmpty()) {
      return result;
    }
    jdbcClient
        .sql(
            "SELECT import_batch_id, id, status FROM import_support_request WHERE import_batch_id"
                + " IN (:batchIds) AND status NOT IN ('CANCELLED','REJECTED')")
        .param("batchIds", batchIds)
        .query(
            rs -> {
              result.put(rs.getLong(1), new ActiveRequest(rs.getLong(2), rs.getString(3)));
            });
    return result;
  }
}
