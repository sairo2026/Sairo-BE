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

/** 유료 이전 지원 운영 큐 조회다(OPS-07). 고객 파일 원본 값은 읽지 않는다. */
@Profile("!migrate")
@Repository
public class OpsImportSupportQueryRepository {

  public record Row(
      long id,
      long officeId,
      long importBatchId,
      String assignedOperatorName,
      String sourceSystem,
      Integer estimatedRowCount,
      Long quoteAmount,
      String status,
      Instant requestedAt,
      Instant deliveredAt) {}

  private static final String COLUMNS =
      "SELECT r.id, r.office_id, r.import_batch_id, a.name AS operator_name, r.source_system,"
          + " r.estimated_row_count, r.quote_amount, r.status, r.requested_at, r.delivered_at"
          + " FROM import_support_request r LEFT JOIN operator_account a ON a.id ="
          + " r.assigned_operator_id";

  private final JdbcClient jdbcClient;

  public OpsImportSupportQueryRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** 끝나지 않은 신청을 오래된 신청 순으로 먼저 보이고 납품·취소·거절은 뒤에 둔다. */
  public List<Row> list(
      String status, Long assignedOperatorId, Long officeId, int limit, long offset) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = where(status, assignedOperatorId, officeId, params);
    var spec =
        jdbcClient.sql(
            COLUMNS
                + where
                + " ORDER BY CASE WHEN r.status IN ('DELIVERED','CANCELLED','REJECTED') THEN 1"
                + " ELSE 0 END, r.requested_at, r.id LIMIT :limit OFFSET :offset");
    for (var entry : params.entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    return spec.param("limit", limit)
        .param("offset", offset)
        .query(OpsImportSupportQueryRepository::map)
        .list();
  }

  public long count(String status, Long assignedOperatorId, Long officeId) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = where(status, assignedOperatorId, officeId, params);
    var spec = jdbcClient.sql("SELECT count(*) FROM import_support_request r" + where);
    for (var entry : params.entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    return spec.query(Long.class).single();
  }

  public Optional<String> batchTargetType(long requestId) {
    return jdbcClient
        .sql(
            "SELECT b.target_type FROM import_support_request r JOIN import_batch b ON b.id ="
                + " r.import_batch_id WHERE r.id = :id")
        .param("id", requestId)
        .query(String.class)
        .optional();
  }

  public Optional<String> operatorName(long operatorId) {
    return jdbcClient
        .sql("SELECT name FROM operator_account WHERE id = :id")
        .param("id", operatorId)
        .query(String.class)
        .optional();
  }

  /** 소속 ID로 구성원 이름을 찾는다. 탈퇴 회원은 이름 대신 정해진 표시를 쓴다. */
  public Optional<String> membershipName(long membershipId) {
    return jdbcClient
        .sql(
            "SELECT CASE WHEN u.account_status = 'WITHDRAWN' THEN '탈퇴한 회원' ELSE u.name END"
                + " FROM office_membership m JOIN app_user u ON u.id = m.user_id WHERE m.id = :id")
        .param("id", membershipId)
        .query(String.class)
        .optional();
  }

  private static String where(
      String status, Long assignedOperatorId, Long officeId, Map<String, Object> params) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    if (status != null) {
      where.append(" AND r.status = :status");
      params.put("status", status);
    }
    if (assignedOperatorId != null) {
      where.append(" AND r.assigned_operator_id = :assigned");
      params.put("assigned", assignedOperatorId);
    }
    if (officeId != null) {
      where.append(" AND r.office_id = :officeId");
      params.put("officeId", officeId);
    }
    return where.toString();
  }

  private static Row map(ResultSet rs, int rowNum) throws SQLException {
    return new Row(
        rs.getLong("id"),
        rs.getLong("office_id"),
        rs.getLong("import_batch_id"),
        rs.getString("operator_name"),
        rs.getString("source_system"),
        rs.getObject("estimated_row_count", Integer.class),
        rs.getObject("quote_amount", Long.class),
        rs.getString("status"),
        instant(rs, "requested_at"),
        instant(rs, "delivered_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
