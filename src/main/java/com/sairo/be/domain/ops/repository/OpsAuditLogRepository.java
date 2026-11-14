package com.sairo.be.domain.ops.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

  public record Row(
      long id,
      long operatorId,
      String operatorName,
      String actionType,
      String targetType,
      Long targetId,
      String detail,
      Instant createdAt) {}

  public record Filter(
      List<String> actionTypes,
      Long operatorId,
      String targetType,
      Long targetId,
      Instant from,
      Instant to) {}

  public List<Row> search(Filter filter, int limit, long offset) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT l.id, l.operator_account_id, o.name, l.action_type, l.target_type,"
                + " l.target_id, l.detail, l.created_at FROM ops_audit_log l JOIN"
                + " operator_account o ON o.id = l.operator_account_id WHERE 1 = 1");
    List<Object[]> params = new ArrayList<>();
    appendFilter(filter, sql, params);
    sql.append(" ORDER BY l.created_at DESC, l.id DESC LIMIT :limit OFFSET :offset");
    var spec = jdbcClient.sql(sql.toString());
    for (Object[] param : params) {
      spec = spec.param((String) param[0], param[1]);
    }
    return spec.param("limit", limit).param("offset", offset).query(this::map).list();
  }

  public long count(Filter filter) {
    StringBuilder sql = new StringBuilder("SELECT count(*) FROM ops_audit_log l WHERE 1 = 1");
    List<Object[]> params = new ArrayList<>();
    appendFilter(filter, sql, params);
    var spec = jdbcClient.sql(sql.toString());
    for (Object[] param : params) {
      spec = spec.param((String) param[0], param[1]);
    }
    return spec.query(Long.class).single();
  }

  private static void appendFilter(Filter filter, StringBuilder sql, List<Object[]> params) {
    if (filter.actionTypes() != null && !filter.actionTypes().isEmpty()) {
      sql.append(" AND l.action_type IN (:actionTypes)");
      params.add(new Object[] {"actionTypes", filter.actionTypes()});
    }
    if (filter.operatorId() != null) {
      sql.append(" AND l.operator_account_id = :operatorId");
      params.add(new Object[] {"operatorId", filter.operatorId()});
    }
    if (filter.targetType() != null) {
      sql.append(" AND l.target_type = :targetType");
      params.add(new Object[] {"targetType", filter.targetType()});
    }
    if (filter.targetId() != null) {
      sql.append(" AND l.target_id = :targetId");
      params.add(new Object[] {"targetId", filter.targetId()});
    }
    if (filter.from() != null) {
      sql.append(" AND l.created_at >= :from");
      params.add(new Object[] {"from", Timestamp.from(filter.from())});
    }
    if (filter.to() != null) {
      sql.append(" AND l.created_at < :to");
      params.add(new Object[] {"to", Timestamp.from(filter.to())});
    }
  }

  private Row map(ResultSet rs, int rowNumber) throws SQLException {
    return new Row(
        rs.getLong("id"),
        rs.getLong("operator_account_id"),
        rs.getString("name"),
        rs.getString("action_type"),
        rs.getString("target_type"),
        rs.getObject("target_id", Long.class),
        rs.getString("detail"),
        rs.getTimestamp("created_at").toInstant());
  }
}
