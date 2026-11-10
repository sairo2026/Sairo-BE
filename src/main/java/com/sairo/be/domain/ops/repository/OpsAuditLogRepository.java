package com.sairo.be.domain.ops.repository;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class OpsAuditLogRepository {

  private final JdbcClient jdbcClient;

  public OpsAuditLogRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public void insert(
      long operatorId,
      String actionType,
      String targetType,
      Long targetId,
      String detail,
      Instant now) {
    jdbcClient
        .sql(
            "INSERT INTO ops_audit_log (operator_account_id, action_type, target_type, target_id,"
                + " detail, created_at) VALUES (:operator, :action, :targetType, :targetId,"
                + " :detail, :now)")
        .param("operator", operatorId)
        .param("action", actionType)
        .param("targetType", targetType)
        .param("targetId", targetId)
        .param("detail", detail)
        .param("now", Timestamp.from(now))
        .update();
  }
}
