package com.sairo.be.domain.ops.repository;

import com.sairo.be.global.support.WithdrawnMemberDisplay;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 운영자가 보는 대표 이전 목록 조회다. */
@Profile("!migrate")
@Repository
public class OpsOwnerTransferQueryRepository {

  public record Row(
      long id,
      long officeId,
      String officeName,
      String fromDisplayName,
      String toDisplayName,
      String status,
      Instant requestedAt,
      Instant acceptDeadlineAt,
      Instant acceptedAt,
      Instant completedAt) {}

  private static final String FROM =
      " FROM office_owner_transfer t JOIN office o ON o.id = t.office_id"
          + " JOIN office_membership fm ON fm.id = t.from_membership_id"
          + " JOIN app_user fu ON fu.id = fm.user_id"
          + " JOIN office_membership tm ON tm.id = t.to_membership_id"
          + " JOIN app_user tu ON tu.id = tm.user_id";

  private final JdbcClient jdbcClient;

  public OpsOwnerTransferQueryRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public List<Row> list(List<String> statuses, Long officeId, int limit, long offset) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = where(statuses, officeId, params);
    var spec =
        jdbcClient.sql(
            "SELECT t.id, t.office_id, o.name AS office_name, t.status, t.requested_at,"
                + " t.accept_deadline_at, t.accepted_at, t.completed_at,"
                + " fu.name AS from_name, fu.account_status AS from_status,"
                + " fm.withdrawn_alias_no AS from_alias, tu.name AS to_name,"
                + " tu.account_status AS to_status, tm.withdrawn_alias_no AS to_alias"
                + FROM
                + where
                + " ORDER BY t.accepted_at ASC NULLS LAST, t.requested_at ASC, t.id ASC"
                + " LIMIT :limit OFFSET :offset");
    for (var entry : params.entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    return spec.param("limit", limit)
        .param("offset", offset)
        .query(OpsOwnerTransferQueryRepository::map)
        .list();
  }

  public long count(List<String> statuses, Long officeId) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = where(statuses, officeId, params);
    var spec = jdbcClient.sql("SELECT count(*) FROM office_owner_transfer t" + where);
    for (var entry : params.entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    return spec.query(Long.class).single();
  }

  private static String where(List<String> statuses, Long officeId, Map<String, Object> params) {
    StringBuilder where = new StringBuilder(" WHERE t.status IN (:statuses)");
    params.put("statuses", statuses);
    if (officeId != null) {
      where.append(" AND t.office_id = :officeId");
      params.put("officeId", officeId);
    }
    return where.toString();
  }

  private static Row map(ResultSet rs, int rowNumber) throws SQLException {
    return new Row(
        rs.getLong("id"),
        rs.getLong("office_id"),
        rs.getString("office_name"),
        WithdrawnMemberDisplay.name(
            "WITHDRAWN".equals(rs.getString("from_status")),
            rs.getString("from_name"),
            (Integer) rs.getObject("from_alias")),
        WithdrawnMemberDisplay.name(
            "WITHDRAWN".equals(rs.getString("to_status")),
            rs.getString("to_name"),
            (Integer) rs.getObject("to_alias")),
        rs.getString("status"),
        rs.getTimestamp("requested_at").toInstant(),
        rs.getTimestamp("accept_deadline_at").toInstant(),
        OpsApprovalRequestRepository.instant(rs.getTimestamp("accepted_at")),
        OpsApprovalRequestRepository.instant(rs.getTimestamp("completed_at")));
  }
}
