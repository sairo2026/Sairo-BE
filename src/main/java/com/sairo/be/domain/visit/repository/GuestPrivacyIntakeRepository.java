package com.sairo.be.domain.visit.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class GuestPrivacyIntakeRepository {

  public record IntakeRow(
      Long officeId,
      String linkKind,
      Long linkId,
      String requestType,
      String requesterRole,
      String contactChannel,
      String contactEncrypted,
      String requestNote,
      Instant receivedAt,
      Instant internalDueAt) {}

  public record AckRow(
      Long officeId,
      String linkKind,
      Long linkId,
      String termsCode,
      String termsVersion,
      Instant acknowledgedAt) {}

  public record AccessLogRow(
      String linkKind,
      Long linkId,
      Long officeId,
      Instant accessedAt,
      String ipHmac,
      String userAgentHash,
      int hmacKeyVersion,
      LocalDate purgeAfter) {}

  private final JdbcClient jdbcClient;

  public GuestPrivacyIntakeRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Optional<Long> officeOfCoordination(Long coordinationId) {
    return jdbcClient
        .sql("SELECT office_id FROM visit_coordination WHERE id = :id")
        .param("id", coordinationId)
        .query(Long.class)
        .optional();
  }

  public Optional<Long> officeOfApproval(Long approvalId) {
    return jdbcClient
        .sql(
            "SELECT p.office_id FROM property_access_approval a"
                + " JOIN property p ON p.id = a.property_id WHERE a.id = :id")
        .param("id", approvalId)
        .query(Long.class)
        .optional();
  }

  public long insertIntake(IntakeRow row) {
    Long id =
        jdbcClient
            .sql(
                "INSERT INTO legal_guest_privacy_intake (office_id, link_kind, link_id,"
                    + " request_type, requester_role, contact_channel, contact_encrypted,"
                    + " request_note, received_at, internal_due_at) VALUES (:officeId, :linkKind,"
                    + " :linkId, :requestType, :requesterRole, :contactChannel, :contactEncrypted,"
                    + " :requestNote, :receivedAt, :internalDueAt) RETURNING id")
            .param("officeId", row.officeId())
            .param("linkKind", row.linkKind())
            .param("linkId", row.linkId())
            .param("requestType", row.requestType())
            .param("requesterRole", row.requesterRole())
            .param("contactChannel", row.contactChannel())
            .param("contactEncrypted", row.contactEncrypted())
            .param("requestNote", row.requestNote())
            .param("receivedAt", Timestamp.from(row.receivedAt()))
            .param("internalDueAt", Timestamp.from(row.internalDueAt()))
            .query(Long.class)
            .single();
    return id == null ? -1 : id;
  }

  public void insertAck(AckRow row) {
    String linkColumn =
        "CUSTOMER".equals(row.linkKind()) ? "customer_link_id" : "approver_property_link_id";
    jdbcClient
        .sql(
            "INSERT INTO consent_guest_notice_ack (office_id, link_kind, "
                + linkColumn
                + ", terms_code, terms_version, action_type, acknowledged_at) VALUES (:officeId,"
                + " :linkKind, :linkId, :termsCode, :termsVersion, 'PRIVACY_INTAKE',"
                + " :acknowledgedAt)")
        .param("officeId", row.officeId())
        .param("linkKind", row.linkKind())
        .param("linkId", row.linkId())
        .param("termsCode", row.termsCode())
        .param("termsVersion", row.termsVersion())
        .param("acknowledgedAt", Timestamp.from(row.acknowledgedAt()))
        .update();
  }

  public void insertAccessLog(AccessLogRow row) {
    jdbcClient
        .sql(
            "INSERT INTO legal_public_link_access_log (accessed_at, link_kind, link_id, office_id,"
                + " action_type, http_status, ip_hmac, user_agent_hash, hmac_key_version,"
                + " purge_after) VALUES (:accessedAt, :linkKind, :linkId, :officeId,"
                + " 'PRIVACY_INTAKE', 201, :ipHmac, :userAgentHash, :hmacKeyVersion, :purgeAfter)")
        .param("accessedAt", Timestamp.from(row.accessedAt()))
        .param("linkKind", row.linkKind())
        .param("linkId", row.linkId())
        .param("officeId", row.officeId())
        .param("ipHmac", row.ipHmac())
        .param("userAgentHash", row.userAgentHash())
        .param("hmacKeyVersion", row.hmacKeyVersion())
        .param("purgeAfter", java.sql.Date.valueOf(row.purgeAfter()))
        .update();
  }
}
