package com.sairo.be.domain.ops.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 운영 승인 요청(ops_approval_request) 가운데 원문 열람·다운로드 요청을 다룬다. */
@Profile("!migrate")
@Repository
public class OpsApprovalRequestRepository {

  public record NewRequest(
      String requestType,
      long requestedBy,
      Long beneficiary,
      String targetType,
      long targetId,
      String purpose,
      String scope,
      String basisType,
      String basisNote,
      Long linkedInquiryId,
      Long officeId) {}

  public record Row(
      long id,
      String requestType,
      long requestedBy,
      String requestedByName,
      Long beneficiary,
      String targetType,
      Long targetId,
      String purpose,
      String scope,
      String basisType,
      String basisNote,
      Long linkedInquiryId,
      Long officeId,
      String storedStatus,
      String status,
      Long approvedBy,
      String approvedByName,
      Instant approvedAt,
      Instant expiresAt,
      Long rejectedBy,
      String rejectedByName,
      Instant rejectedAt,
      String rejectReason,
      Long grantId,
      Instant createdAt) {

    /** 실행할 수 있는 운영자다. 수혜자가 없으면 요청자다. */
    public long effectiveExecutor() {
      return beneficiary != null ? beneficiary : requestedBy;
    }
  }

  /** 실행 기록이 없고 승인 유효기한을 지난 승인은 EXPIRED로 본다. */
  static final String RAW_TYPES = "('RAW_VIEW','RAW_DOWNLOAD')";

  private static final String EFFECTIVE_STATUS =
      "CASE WHEN r.status = 'APPROVED' AND g.id IS NULL AND r.expires_at <= :now THEN 'EXPIRED'"
          + " ELSE r.status END";

  private static final String SELECT =
      "SELECT r.id, r.request_type, r.requested_by_operator_id, req.name AS requested_by_name,"
          + " r.beneficiary_operator_id, r.target_type, r.target_id, r.purpose, r.scope,"
          + " r.basis_type, r.basis_note, r.linked_inquiry_id, r.office_id, r.status AS stored,"
          + " "
          + EFFECTIVE_STATUS
          + " AS status, r.approved_by_operator_id, apr.name AS approved_by_name, r.approved_at,"
          + " r.expires_at, r.rejected_by_operator_id, rej.name AS rejected_by_name,"
          + " r.rejected_at, r.reject_reason, g.id AS grant_id, r.created_at"
          + " FROM ops_approval_request r"
          + " JOIN operator_account req ON req.id = r.requested_by_operator_id"
          + " LEFT JOIN operator_account apr ON apr.id = r.approved_by_operator_id"
          + " LEFT JOIN operator_account rej ON rej.id = r.rejected_by_operator_id"
          + " LEFT JOIN ops_raw_access_grant g ON g.approval_request_id = r.id"
          + " WHERE r.request_type IN "
          + RAW_TYPES;

  private final JdbcClient jdbcClient;

  public OpsApprovalRequestRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public long insert(NewRequest request, Instant now) {
    return jdbcClient
        .sql(
            "INSERT INTO ops_approval_request (request_type, requested_by_operator_id,"
                + " beneficiary_operator_id, target_type, target_id, purpose, scope, basis_type,"
                + " basis_note, linked_inquiry_id, office_id, status, created_at) VALUES (:type,"
                + " :requestedBy, :beneficiary, :targetType, :targetId, :purpose, :scope,"
                + " :basisType, :basisNote, :inquiry, :office, 'PENDING', :now) RETURNING id")
        .param("type", request.requestType())
        .param("requestedBy", request.requestedBy())
        .param("beneficiary", request.beneficiary())
        .param("targetType", request.targetType())
        .param("targetId", request.targetId())
        .param("purpose", request.purpose())
        .param("scope", request.scope())
        .param("basisType", request.basisType())
        .param("basisNote", request.basisNote())
        .param("inquiry", request.linkedInquiryId())
        .param("office", request.officeId())
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  public Optional<Row> find(long id, Instant now) {
    return jdbcClient
        .sql(SELECT + " AND r.id = :id")
        .param("id", id)
        .param("now", Timestamp.from(now))
        .query(this::map)
        .optional();
  }

  public Optional<Row> lock(long id, Instant now) {
    jdbcClient
        .sql(
            "SELECT id FROM ops_approval_request WHERE id = :id AND request_type IN "
                + RAW_TYPES
                + " FOR UPDATE")
        .param("id", id)
        .query(Long.class)
        .optional();
    return find(id, now);
  }

  /** MINE은 요청자·수혜자가 나인 건, APPROVABLE은 요청자·수혜자가 내가 아닌 대기 건이다. */
  public List<Row> list(
      boolean approvable, long operatorId, String status, Instant now, int limit, long offset) {
    return jdbcClient
        .sql(
            SELECT
                + listCondition(approvable)
                + " AND (CAST(:status AS VARCHAR) IS NULL OR "
                + EFFECTIVE_STATUS
                + " = :status) ORDER BY r.created_at DESC, r.id DESC LIMIT :limit OFFSET :offset")
        .param("me", operatorId)
        .param("status", status)
        .param("now", Timestamp.from(now))
        .param("limit", limit)
        .param("offset", offset)
        .query(this::map)
        .list();
  }

  public long count(boolean approvable, long operatorId, String status, Instant now) {
    return jdbcClient
        .sql(
            "SELECT count(*) FROM ops_approval_request r LEFT JOIN ops_raw_access_grant g"
                + " ON g.approval_request_id = r.id WHERE r.request_type IN "
                + RAW_TYPES
                + listCondition(approvable)
                + " AND (CAST(:status AS VARCHAR) IS NULL OR "
                + EFFECTIVE_STATUS
                + " = :status)")
        .param("me", operatorId)
        .param("status", status)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  private static String listCondition(boolean approvable) {
    if (approvable) {
      return " AND r.status = 'PENDING' AND r.requested_by_operator_id <> :me"
          + " AND (r.beneficiary_operator_id IS NULL OR r.beneficiary_operator_id <> :me)";
    }
    return " AND (r.requested_by_operator_id = :me OR r.beneficiary_operator_id = :me)";
  }

  public void approve(long id, long approverId, Instant approvedAt, Instant expiresAt) {
    jdbcClient
        .sql(
            "UPDATE ops_approval_request SET status = 'APPROVED', approved_by_operator_id ="
                + " :approver, approved_at = :approvedAt, expires_at = :expiresAt WHERE id = :id")
        .param("approver", approverId)
        .param("approvedAt", Timestamp.from(approvedAt))
        .param("expiresAt", Timestamp.from(expiresAt))
        .param("id", id)
        .update();
  }

  public void reject(long id, long operatorId, String reason, Instant now) {
    jdbcClient
        .sql(
            "UPDATE ops_approval_request SET status = 'REJECTED', rejected_by_operator_id ="
                + " :operator, rejected_at = :now, reject_reason = :reason WHERE id = :id")
        .param("operator", operatorId)
        .param("now", Timestamp.from(now))
        .param("reason", reason)
        .param("id", id)
        .update();
  }

  public void markStatus(long id, String status) {
    jdbcClient
        .sql("UPDATE ops_approval_request SET status = :status WHERE id = :id")
        .param("status", status)
        .param("id", id)
        .update();
  }

  /** 운영자가 요청자·수혜자·승인자인 미실행 원문 접근 요청을 무효로 만들고 건수를 돌려준다. */
  public int invalidateUnexecutedFor(long operatorId, Instant now) {
    return jdbcClient
        .sql(
            "UPDATE ops_approval_request r SET status = 'INVALIDATED' WHERE r.request_type IN "
                + RAW_TYPES
                + " AND (r.status = 'PENDING' OR (r.status = 'APPROVED' AND r.expires_at > :now))"
                + " AND NOT EXISTS (SELECT 1 FROM ops_raw_access_grant g"
                + " WHERE g.approval_request_id = r.id)"
                + " AND (r.requested_by_operator_id = :id OR r.beneficiary_operator_id = :id"
                + " OR r.approved_by_operator_id = :id)")
        .param("id", operatorId)
        .param("now", Timestamp.from(now))
        .update();
  }

  /** 대상 회원이 정지되면 그 회원을 대상으로 한 미실행 원문 접근 요청을 무효로 만든다. */
  public int invalidateUnexecutedForTarget(String targetType, long targetId, Instant now) {
    return jdbcClient
        .sql(
            "UPDATE ops_approval_request r SET status = 'INVALIDATED' WHERE r.request_type IN "
                + RAW_TYPES
                + " AND (r.status = 'PENDING' OR (r.status = 'APPROVED' AND r.expires_at > :now))"
                + " AND NOT EXISTS (SELECT 1 FROM ops_raw_access_grant g"
                + " WHERE g.approval_request_id = r.id)"
                + " AND r.target_type = :targetType AND r.target_id = :targetId")
        .param("targetType", targetType)
        .param("targetId", targetId)
        .param("now", Timestamp.from(now))
        .update();
  }

  private Row map(ResultSet rs, int rowNumber) throws SQLException {
    return new Row(
        rs.getLong("id"),
        rs.getString("request_type"),
        rs.getLong("requested_by_operator_id"),
        rs.getString("requested_by_name"),
        rs.getObject("beneficiary_operator_id", Long.class),
        rs.getString("target_type"),
        rs.getObject("target_id", Long.class),
        rs.getString("purpose"),
        rs.getString("scope"),
        rs.getString("basis_type"),
        rs.getString("basis_note"),
        rs.getObject("linked_inquiry_id", Long.class),
        rs.getObject("office_id", Long.class),
        rs.getString("stored"),
        rs.getString("status"),
        rs.getObject("approved_by_operator_id", Long.class),
        rs.getString("approved_by_name"),
        instant(rs.getTimestamp("approved_at")),
        instant(rs.getTimestamp("expires_at")),
        rs.getObject("rejected_by_operator_id", Long.class),
        rs.getString("rejected_by_name"),
        instant(rs.getTimestamp("rejected_at")),
        rs.getString("reject_reason"),
        rs.getObject("grant_id", Long.class),
        rs.getTimestamp("created_at").toInstant());
  }

  static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
