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

/** 개인정보 권리 요청 큐 조회와 반환·보관권한 현황·변경이다(OPS-07). 요청자 식별 값은 읽지 않는다. */
@Profile("!migrate")
@Repository
public class OpsPrivacyOpsRepository {

  public record RequestRow(
      long id,
      Long officeId,
      String requestType,
      String identityStatus,
      String status,
      Instant receivedAt,
      String legalBasis,
      String requestScope,
      Instant legalDueAt,
      Instant internalDueAt,
      String decisionReason,
      String completionEvidence,
      Long assignedOperatorId,
      String assignedOperatorName,
      Instant completedAt,
      String handlerType,
      long version) {}

  public record EventRow(
      long id,
      String eventType,
      String previousStatus,
      String newStatus,
      String actionDetail,
      String actorOperatorName,
      Instant occurredAt) {}

  public record GrantRow(
      long id,
      long officeId,
      long grantedToUserId,
      String contractReference,
      Instant grantedAt,
      Instant expiresAt,
      Instant endedAt,
      Long replacedByGrantId) {}

  public record NewGrant(
      long officeId,
      long userId,
      String contractReference,
      Instant grantedAt,
      Instant expiresAt,
      String identityEvidenceReference,
      String registrationRelationReference) {}

  public record RequestFilter(
      String status, String requestType, Long officeId, Long assignedOperatorId) {}

  private static final String REQUEST_COLUMNS =
      "SELECT r.id, r.office_id, r.request_type, r.identity_status, r.status, r.received_at,"
          + " r.legal_basis, r.request_scope, r.legal_due_at, r.internal_due_at,"
          + " r.decision_reason, r.completion_evidence, r.assigned_operator_id, a.name AS"
          + " operator_name, r.completed_at, r.handler_type, r.version FROM privacy_request r LEFT"
          + " JOIN"
          + " operator_account a ON a.id = r.assigned_operator_id";

  private final JdbcClient jdbcClient;

  public OpsPrivacyOpsRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** 끝나지 않은 요청을 법정 기한이 가까운 순으로 먼저 보이고 완료·거절은 최근 접수 순으로 뒤에 둔다. */
  public List<RequestRow> requests(RequestFilter filter, int limit, long offset) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = requestWhere(filter, params);
    var spec =
        jdbcClient.sql(
            REQUEST_COLUMNS
                + where
                + " ORDER BY CASE WHEN r.status IN ('COMPLETED','REJECTED') THEN 1 ELSE 0 END,"
                + " CASE WHEN r.status IN ('COMPLETED','REJECTED') THEN NULL ELSE r.legal_due_at"
                + " END, r.received_at DESC, r.id DESC LIMIT :limit OFFSET :offset");
    for (var entry : params.entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    return spec.param("limit", limit)
        .param("offset", offset)
        .query(OpsPrivacyOpsRepository::mapRequest)
        .list();
  }

  public long countRequests(RequestFilter filter) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = requestWhere(filter, params);
    var spec = jdbcClient.sql("SELECT count(*) FROM privacy_request r" + where);
    for (var entry : params.entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    return spec.query(Long.class).single();
  }

  public Optional<RequestRow> request(long requestId) {
    return jdbcClient
        .sql(REQUEST_COLUMNS + " WHERE r.id = :id")
        .param("id", requestId)
        .query(OpsPrivacyOpsRepository::mapRequest)
        .optional();
  }

  public List<EventRow> events(long requestId) {
    return jdbcClient
        .sql(
            "SELECT e.id, e.event_type, e.previous_status, e.new_status, e.action_detail, a.name"
                + " AS operator_name, e.occurred_at FROM privacy_request_event e LEFT JOIN"
                + " operator_account a ON a.id = e.actor_operator_id WHERE e.privacy_request_id ="
                + " :id ORDER BY e.occurred_at, e.id")
        .param("id", requestId)
        .query(
            (rs, rowNum) ->
                new EventRow(
                    rs.getLong("id"),
                    rs.getString("event_type"),
                    rs.getString("previous_status"),
                    rs.getString("new_status"),
                    rs.getString("action_detail"),
                    rs.getString("operator_name"),
                    instant(rs, "occurred_at")))
        .list();
  }

  public List<GrantRow> returnGrants(
      Long officeId, boolean activeOnly, Instant now, int limit, long offset) {
    return grants("return_grant", "revoked_at", "NULL", officeId, activeOnly, now, limit, offset);
  }

  public long countReturnGrants(Long officeId, boolean activeOnly, Instant now) {
    return countGrants("return_grant", "revoked_at", officeId, activeOnly, now);
  }

  public List<GrantRow> archiveGrants(
      Long officeId, boolean activeOnly, Instant now, int limit, long offset) {
    return grants(
        "archive_grant",
        "terminated_at",
        "contract_reference",
        officeId,
        activeOnly,
        now,
        limit,
        offset);
  }

  public long countArchiveGrants(Long officeId, boolean activeOnly, Instant now) {
    return countGrants("archive_grant", "terminated_at", officeId, activeOnly, now);
  }

  public Optional<GrantRow> lockReturnGrant(long grantId) {
    return lockGrant("return_grant", "revoked_at", "NULL", grantId);
  }

  public Optional<GrantRow> lockArchiveGrant(long grantId) {
    return lockGrant("archive_grant", "terminated_at", "contract_reference", grantId);
  }

  public long insertReturnGrant(NewGrant grant) {
    return jdbcClient
        .sql(
            "INSERT INTO return_grant (office_id, granted_to_user_id, granted_at, expires_at,"
                + " identity_evidence_reference, registration_relation_reference) VALUES (:office,"
                + " :user, :grantedAt, :expiresAt, :identity, :relation) RETURNING id")
        .param("office", grant.officeId())
        .param("user", grant.userId())
        .param("grantedAt", Timestamp.from(grant.grantedAt()))
        .param("expiresAt", Timestamp.from(grant.expiresAt()))
        .param("identity", grant.identityEvidenceReference())
        .param("relation", grant.registrationRelationReference())
        .query(Long.class)
        .single();
  }

  public long insertArchiveGrant(NewGrant grant) {
    return jdbcClient
        .sql(
            "INSERT INTO archive_grant (office_id, granted_to_user_id, contract_reference,"
                + " granted_at, expires_at, identity_evidence_reference,"
                + " registration_relation_reference) VALUES (:office, :user, :contract, :grantedAt,"
                + " :expiresAt, :identity, :relation) RETURNING id")
        .param("office", grant.officeId())
        .param("user", grant.userId())
        .param("contract", grant.contractReference())
        .param("grantedAt", Timestamp.from(grant.grantedAt()))
        .param("expiresAt", grant.expiresAt() == null ? null : Timestamp.from(grant.expiresAt()))
        .param("identity", grant.identityEvidenceReference())
        .param("relation", grant.registrationRelationReference())
        .query(Long.class)
        .single();
  }

  public void revokeReturnGrant(long grantId, String reason, long replacedBy, Instant now) {
    jdbcClient
        .sql(
            "UPDATE return_grant SET revoked_at = :now, revoked_reason = :reason,"
                + " replaced_by_grant_id = :replacedBy WHERE id = :id")
        .param("now", Timestamp.from(now))
        .param("reason", reason)
        .param("replacedBy", replacedBy)
        .param("id", grantId)
        .update();
  }

  public void terminateArchiveGrant(long grantId, String reason, long replacedBy, Instant now) {
    jdbcClient
        .sql(
            "UPDATE archive_grant SET terminated_at = :now, terminated_reason = :reason,"
                + " replaced_by_grant_id = :replacedBy WHERE id = :id")
        .param("now", Timestamp.from(now))
        .param("reason", reason)
        .param("replacedBy", replacedBy)
        .param("id", grantId)
        .update();
  }

  public boolean activeUser(long userId) {
    return jdbcClient
            .sql("SELECT count(*) FROM app_user WHERE id = :id AND account_status = 'ACTIVE'")
            .param("id", userId)
            .query(Long.class)
            .single()
        > 0;
  }

  private List<GrantRow> grants(
      String table,
      String endColumn,
      String contractColumn,
      Long officeId,
      boolean activeOnly,
      Instant now,
      int limit,
      long offset) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = grantWhere(endColumn, officeId, activeOnly, now, params);
    var spec =
        jdbcClient.sql(
            grantColumns(table, endColumn, contractColumn)
                + where
                + " ORDER BY granted_at DESC, id DESC LIMIT :limit OFFSET :offset");
    for (var entry : params.entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    return spec.param("limit", limit)
        .param("offset", offset)
        .query(OpsPrivacyOpsRepository::mapGrant)
        .list();
  }

  private long countGrants(
      String table, String endColumn, Long officeId, boolean activeOnly, Instant now) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = grantWhere(endColumn, officeId, activeOnly, now, params);
    var spec = jdbcClient.sql("SELECT count(*) FROM " + table + where);
    for (var entry : params.entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    return spec.query(Long.class).single();
  }

  private Optional<GrantRow> lockGrant(
      String table, String endColumn, String contractColumn, long grantId) {
    return jdbcClient
        .sql(grantColumns(table, endColumn, contractColumn) + " WHERE id = :id FOR UPDATE")
        .param("id", grantId)
        .query(OpsPrivacyOpsRepository::mapGrant)
        .optional();
  }

  private static String grantColumns(String table, String endColumn, String contractColumn) {
    return "SELECT id, office_id, granted_to_user_id, "
        + contractColumn
        + " AS contract_reference, granted_at, expires_at, "
        + endColumn
        + " AS ended_at, replaced_by_grant_id FROM "
        + table;
  }

  private static String grantWhere(
      String endColumn,
      Long officeId,
      boolean activeOnly,
      Instant now,
      Map<String, Object> params) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    if (officeId != null) {
      where.append(" AND office_id = :officeId");
      params.put("officeId", officeId);
    }
    if (activeOnly) {
      where
          .append(" AND ")
          .append(endColumn)
          .append(" IS NULL AND (expires_at IS NULL OR expires_at > :now)");
      params.put("now", Timestamp.from(now));
    }
    return where.toString();
  }

  private static String requestWhere(RequestFilter filter, Map<String, Object> params) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    if (filter.status() != null) {
      where.append(" AND r.status = :status");
      params.put("status", filter.status());
    }
    if (filter.requestType() != null) {
      where.append(" AND r.request_type = :requestType");
      params.put("requestType", filter.requestType());
    }
    if (filter.officeId() != null) {
      where.append(" AND r.office_id = :officeId");
      params.put("officeId", filter.officeId());
    }
    if (filter.assignedOperatorId() != null) {
      where.append(" AND r.assigned_operator_id = :assigned");
      params.put("assigned", filter.assignedOperatorId());
    }
    return where.toString();
  }

  private static RequestRow mapRequest(ResultSet rs, int rowNum) throws SQLException {
    return new RequestRow(
        rs.getLong("id"),
        rs.getObject("office_id", Long.class),
        rs.getString("request_type"),
        rs.getString("identity_status"),
        rs.getString("status"),
        instant(rs, "received_at"),
        rs.getString("legal_basis"),
        rs.getString("request_scope"),
        instant(rs, "legal_due_at"),
        instant(rs, "internal_due_at"),
        rs.getString("decision_reason"),
        rs.getString("completion_evidence"),
        rs.getObject("assigned_operator_id", Long.class),
        rs.getString("operator_name"),
        instant(rs, "completed_at"),
        rs.getString("handler_type"),
        rs.getLong("version"));
  }

  private static GrantRow mapGrant(ResultSet rs, int rowNum) throws SQLException {
    return new GrantRow(
        rs.getLong("id"),
        rs.getLong("office_id"),
        rs.getLong("granted_to_user_id"),
        rs.getString("contract_reference"),
        instant(rs, "granted_at"),
        instant(rs, "expires_at"),
        instant(rs, "ended_at"),
        rs.getObject("replaced_by_grant_id", Long.class));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
