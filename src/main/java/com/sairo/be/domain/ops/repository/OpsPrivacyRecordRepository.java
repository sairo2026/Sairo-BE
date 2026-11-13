package com.sairo.be.domain.ops.repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 비회원 권리 요청 접수, 수사기관 등 자료 요청, 공급자 계약서 사본 기록이다(OPS-15·16·17). */
@Profile("!migrate")
@Repository
public class OpsPrivacyRecordRepository {

  public record IntakeRow(
      long id,
      long officeId,
      String officeName,
      String linkKind,
      String requestType,
      String requesterRole,
      String contactChannel,
      String contactEncrypted,
      String verificationStatus,
      String status,
      Instant receivedAt,
      Instant internalDueAt,
      Long privacyRequestId,
      String closedReason) {}

  public record AuthorityRow(
      long id,
      Instant receivedAt,
      String agencyType,
      String agencyName,
      String documentReference,
      String legalBasisNote,
      Long targetOfficeId,
      Long targetUserId,
      String requestedItems,
      String decision,
      String providedItems,
      Instant providedAt,
      String officeNoticeStatus,
      long recordedByOperatorId,
      String recordedByOperatorName,
      Long approvedByOperatorId,
      Instant closedAt,
      LocalDate retentionUntil,
      String closeNote) {}

  public record NewAuthority(
      Instant receivedAt,
      String agencyType,
      String agencyName,
      String documentReference,
      String legalBasisNote,
      Long targetOfficeId,
      Long targetUserId,
      String requestedItems,
      long recordedByOperatorId) {}

  public record AuthorityClose(
      String decision,
      String providedItems,
      Instant providedAt,
      String officeNoticeStatus,
      Long approvedByOperatorId,
      Instant closedAt,
      LocalDate retentionUntil,
      String closeNote) {}

  public record ContractCopyRow(
      long id,
      long integrationId,
      String documentKind,
      String versionLabel,
      LocalDate signedOn,
      LocalDate validUntil,
      String contentSha256,
      Instant subprocessorChangeNotifiedAt,
      long uploadedByOperatorId,
      Instant uploadedAt,
      LocalDate retentionUntil) {}

  public record NewContractCopy(
      long integrationId,
      String documentKind,
      String versionLabel,
      LocalDate signedOn,
      LocalDate validUntil,
      String storageKey,
      String contentSha256,
      Instant subprocessorChangeNotifiedAt,
      long uploadedByOperatorId,
      Instant uploadedAt,
      LocalDate retentionUntil) {}

  private static final String INTAKE_COLUMNS =
      "SELECT g.id, g.office_id, o.name AS office_name, g.link_kind, g.request_type,"
          + " g.requester_role, g.contact_channel, g.contact_encrypted, g.verification_status,"
          + " g.status, g.received_at, g.internal_due_at, g.privacy_request_id, g.closed_reason"
          + " FROM legal_guest_privacy_intake g JOIN office o ON o.id = g.office_id";

  private static final String AUTHORITY_COLUMNS =
      "SELECT r.id, r.received_at, r.agency_type, r.agency_name, r.document_reference,"
          + " r.legal_basis_note, r.target_office_id, r.target_user_id, r.requested_items,"
          + " r.decision, r.provided_items, r.provided_at, r.office_notice_status,"
          + " r.recorded_by_operator_id, a.name AS recorder_name, r.approved_by_operator_id,"
          + " r.closed_at, r.retention_until, r.close_note FROM legal_authority_request r JOIN"
          + " operator_account a ON a.id = r.recorded_by_operator_id";

  private static final String COPY_COLUMNS =
      "SELECT id, integration_id, document_kind, version_label, signed_on, valid_until,"
          + " content_sha256, subprocessor_change_notified_at, uploaded_by_operator_id,"
          + " uploaded_at, retention_until FROM legal_provider_contract_copy";

  private final JdbcClient jdbcClient;

  public OpsPrivacyRecordRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public List<IntakeRow> intakes(String status, Long officeId, int limit, long offset) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = intakeWhere(status, officeId, params);
    var spec =
        jdbcClient.sql(
            INTAKE_COLUMNS
                + where
                + " ORDER BY CASE g.status WHEN 'RECEIVED' THEN 0 ELSE 1 END, g.internal_due_at,"
                + " g.id LIMIT :limit OFFSET :offset");
    for (var entry : params.entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    return spec.param("limit", limit)
        .param("offset", offset)
        .query(OpsPrivacyRecordRepository::mapIntake)
        .list();
  }

  public long countIntakes(String status, Long officeId) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = intakeWhere(status, officeId, params);
    var spec = jdbcClient.sql("SELECT count(*) FROM legal_guest_privacy_intake g" + where);
    for (var entry : params.entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    return spec.query(Long.class).single();
  }

  private static String intakeWhere(String status, Long officeId, Map<String, Object> params) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    if (status != null) {
      where.append(" AND g.status = :status");
      params.put("status", status);
    }
    if (officeId != null) {
      where.append(" AND g.office_id = :officeId");
      params.put("officeId", officeId);
    }
    return where.toString();
  }

  public Optional<IntakeRow> intake(long intakeId) {
    return jdbcClient
        .sql(INTAKE_COLUMNS + " WHERE g.id = :id")
        .param("id", intakeId)
        .query(OpsPrivacyRecordRepository::mapIntake)
        .optional();
  }

  public Optional<IntakeRow> lockIntake(long intakeId) {
    return jdbcClient
        .sql(INTAKE_COLUMNS + " WHERE g.id = :id FOR UPDATE OF g")
        .param("id", intakeId)
        .query(OpsPrivacyRecordRepository::mapIntake)
        .optional();
  }

  public void convertIntake(long intakeId, String verificationStatus, long privacyRequestId) {
    jdbcClient
        .sql(
            "UPDATE legal_guest_privacy_intake SET verification_status = :verification, status ="
                + " 'CONVERTED', privacy_request_id = :requestId WHERE id = :id")
        .param("verification", verificationStatus)
        .param("requestId", privacyRequestId)
        .param("id", intakeId)
        .update();
  }

  public void closeIntake(
      long intakeId, String verificationStatus, String closedReason, Instant closedAt) {
    jdbcClient
        .sql(
            "UPDATE legal_guest_privacy_intake SET verification_status = :verification, status ="
                + " 'CLOSED', closed_reason = :reason, closed_at = :closedAt WHERE id = :id")
        .param("verification", verificationStatus)
        .param("reason", closedReason)
        .param("closedAt", Timestamp.from(closedAt))
        .param("id", intakeId)
        .update();
  }

  public List<AuthorityRow> authorityRequests(
      String decision, boolean openOnly, int limit, long offset) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = authorityWhere(decision, openOnly, params);
    var spec =
        jdbcClient.sql(
            AUTHORITY_COLUMNS
                + where
                + " ORDER BY r.received_at DESC, r.id DESC LIMIT :limit OFFSET :offset");
    for (var entry : params.entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    return spec.param("limit", limit)
        .param("offset", offset)
        .query(OpsPrivacyRecordRepository::mapAuthority)
        .list();
  }

  public long countAuthorityRequests(String decision, boolean openOnly) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = authorityWhere(decision, openOnly, params);
    var spec = jdbcClient.sql("SELECT count(*) FROM legal_authority_request r" + where);
    for (var entry : params.entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    return spec.query(Long.class).single();
  }

  private static String authorityWhere(
      String decision, boolean openOnly, Map<String, Object> params) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    if (decision != null) {
      where.append(" AND r.decision = :decision");
      params.put("decision", decision);
    }
    if (openOnly) {
      where.append(" AND r.closed_at IS NULL");
    }
    return where.toString();
  }

  public long insertAuthorityRequest(NewAuthority row, Instant now) {
    return jdbcClient
        .sql(
            "INSERT INTO legal_authority_request (received_at, agency_type, agency_name,"
                + " document_reference, legal_basis_note, target_office_id, target_user_id,"
                + " requested_items, recorded_by_operator_id, created_at) VALUES (:receivedAt,"
                + " :agencyType, :agencyName, :documentReference, :legalBasisNote,"
                + " :targetOfficeId, :targetUserId, :requestedItems, :recordedBy, :now)"
                + " RETURNING id")
        .param("receivedAt", Timestamp.from(row.receivedAt()))
        .param("agencyType", row.agencyType())
        .param("agencyName", row.agencyName())
        .param("documentReference", row.documentReference())
        .param("legalBasisNote", row.legalBasisNote())
        .param("targetOfficeId", row.targetOfficeId())
        .param("targetUserId", row.targetUserId())
        .param("requestedItems", row.requestedItems())
        .param("recordedBy", row.recordedByOperatorId())
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  public Optional<AuthorityRow> authorityRequest(long requestId, boolean lock) {
    return jdbcClient
        .sql(AUTHORITY_COLUMNS + " WHERE r.id = :id" + (lock ? " FOR UPDATE OF r" : ""))
        .param("id", requestId)
        .query(OpsPrivacyRecordRepository::mapAuthority)
        .optional();
  }

  public void closeAuthorityRequest(long requestId, AuthorityClose close) {
    jdbcClient
        .sql(
            "UPDATE legal_authority_request SET decision = :decision, provided_items = :items,"
                + " provided_at = :providedAt, office_notice_status = :notice,"
                + " approved_by_operator_id = :approvedBy, closed_at = :closedAt,"
                + " retention_until = :retentionUntil, close_note = :closeNote WHERE id = :id")
        .param("decision", close.decision())
        .param("items", close.providedItems())
        .param("providedAt", close.providedAt() == null ? null : Timestamp.from(close.providedAt()))
        .param("notice", close.officeNoticeStatus())
        .param("approvedBy", close.approvedByOperatorId())
        .param("closedAt", Timestamp.from(close.closedAt()))
        .param("retentionUntil", Date.valueOf(close.retentionUntil()))
        .param("closeNote", close.closeNote())
        .param("id", requestId)
        .update();
  }

  public boolean officeExists(long officeId) {
    return exists("SELECT count(*) FROM office WHERE id = :id", officeId);
  }

  public boolean userExists(long userId) {
    return exists("SELECT count(*) FROM app_user WHERE id = :id", userId);
  }

  public boolean integrationExists(long integrationId) {
    return exists("SELECT count(*) FROM integration_config WHERE id = :id", integrationId);
  }

  public List<ContractCopyRow> contractCopies(long integrationId) {
    return jdbcClient
        .sql(COPY_COLUMNS + " WHERE integration_id = :id ORDER BY uploaded_at DESC, id DESC")
        .param("id", integrationId)
        .query(OpsPrivacyRecordRepository::mapCopy)
        .list();
  }

  public boolean contractCopyExists(long integrationId, String documentKind, String versionLabel) {
    return jdbcClient
            .sql(
                "SELECT count(*) FROM legal_provider_contract_copy WHERE integration_id = :id AND"
                    + " document_kind = :kind AND version_label = :label")
            .param("id", integrationId)
            .param("kind", documentKind)
            .param("label", versionLabel)
            .query(Long.class)
            .single()
        > 0;
  }

  public ContractCopyRow insertContractCopy(NewContractCopy row) {
    long id =
        jdbcClient
            .sql(
                "INSERT INTO legal_provider_contract_copy (integration_id, document_kind,"
                    + " version_label, signed_on, valid_until, storage_key, content_sha256,"
                    + " subprocessor_change_notified_at, uploaded_by_operator_id, uploaded_at,"
                    + " retention_until) VALUES (:integrationId, :kind, :label, :signedOn,"
                    + " :validUntil, :storageKey, :sha, :notifiedAt, :uploadedBy, :uploadedAt,"
                    + " :retentionUntil) RETURNING id")
            .param("integrationId", row.integrationId())
            .param("kind", row.documentKind())
            .param("label", row.versionLabel())
            .param("signedOn", row.signedOn() == null ? null : Date.valueOf(row.signedOn()))
            .param("validUntil", row.validUntil() == null ? null : Date.valueOf(row.validUntil()))
            .param("storageKey", row.storageKey())
            .param("sha", row.contentSha256())
            .param(
                "notifiedAt",
                row.subprocessorChangeNotifiedAt() == null
                    ? null
                    : Timestamp.from(row.subprocessorChangeNotifiedAt()))
            .param("uploadedBy", row.uploadedByOperatorId())
            .param("uploadedAt", Timestamp.from(row.uploadedAt()))
            .param(
                "retentionUntil",
                row.retentionUntil() == null ? null : Date.valueOf(row.retentionUntil()))
            .query(Long.class)
            .single();
    return jdbcClient
        .sql(COPY_COLUMNS + " WHERE id = :id")
        .param("id", id)
        .query(OpsPrivacyRecordRepository::mapCopy)
        .single();
  }

  private boolean exists(String sql, long id) {
    return jdbcClient.sql(sql).param("id", id).query(Long.class).single() > 0;
  }

  private static IntakeRow mapIntake(ResultSet rs, int row) throws SQLException {
    return new IntakeRow(
        rs.getLong("id"),
        rs.getLong("office_id"),
        rs.getString("office_name"),
        rs.getString("link_kind"),
        rs.getString("request_type"),
        rs.getString("requester_role"),
        rs.getString("contact_channel"),
        rs.getString("contact_encrypted"),
        rs.getString("verification_status"),
        rs.getString("status"),
        instant(rs.getTimestamp("received_at")),
        instant(rs.getTimestamp("internal_due_at")),
        rs.getObject("privacy_request_id", Long.class),
        rs.getString("closed_reason"));
  }

  private static AuthorityRow mapAuthority(ResultSet rs, int row) throws SQLException {
    return new AuthorityRow(
        rs.getLong("id"),
        instant(rs.getTimestamp("received_at")),
        rs.getString("agency_type"),
        rs.getString("agency_name"),
        rs.getString("document_reference"),
        rs.getString("legal_basis_note"),
        rs.getObject("target_office_id", Long.class),
        rs.getObject("target_user_id", Long.class),
        rs.getString("requested_items"),
        rs.getString("decision"),
        rs.getString("provided_items"),
        instant(rs.getTimestamp("provided_at")),
        rs.getString("office_notice_status"),
        rs.getLong("recorded_by_operator_id"),
        rs.getString("recorder_name"),
        rs.getObject("approved_by_operator_id", Long.class),
        instant(rs.getTimestamp("closed_at")),
        date(rs.getDate("retention_until")),
        rs.getString("close_note"));
  }

  private static ContractCopyRow mapCopy(ResultSet rs, int row) throws SQLException {
    return new ContractCopyRow(
        rs.getLong("id"),
        rs.getLong("integration_id"),
        rs.getString("document_kind"),
        rs.getString("version_label"),
        date(rs.getDate("signed_on")),
        date(rs.getDate("valid_until")),
        rs.getString("content_sha256"),
        instant(rs.getTimestamp("subprocessor_change_notified_at")),
        rs.getLong("uploaded_by_operator_id"),
        instant(rs.getTimestamp("uploaded_at")),
        date(rs.getDate("retention_until")));
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static LocalDate date(Date date) {
    return date == null ? null : date.toLocalDate();
  }
}
