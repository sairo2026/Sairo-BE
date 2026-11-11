package com.sairo.be.domain.contract.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 계약 서류(contract_document)다. 서류 행은 고치거나 지우지 않고 정정본을 새 버전으로 더한다. */
@Profile("!migrate")
@Repository
public class ContractDocumentRepository {

  public record DocumentRow(
      Long id,
      Long contractId,
      Long contractRevisionId,
      String documentType,
      int versionNo,
      Long supersedesDocumentId,
      LocalDate writtenDate,
      LocalDate deliveredDate,
      String datePendingReason,
      LocalDate retentionEndDate,
      int retentionYears,
      String objectKey,
      boolean piiMaskingConfirmed) {}

  public record NewDocument(
      Long contractId,
      Long contractRevisionId,
      String documentType,
      int versionNo,
      Long supersedesDocumentId,
      LocalDate writtenDate,
      LocalDate deliveredDate,
      String datePendingReason,
      LocalDate retentionEndDate,
      int retentionYears,
      String objectKey,
      boolean piiMaskingConfirmed,
      Long uploadedBy) {}

  private final JdbcClient jdbcClient;

  public ContractDocumentRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Long insert(NewDocument d, Instant now) {
    return jdbcClient
        .sql(
            "INSERT INTO contract_document (contract_id, contract_revision_id, document_type,"
                + " version_no, supersedes_document_id, written_date, delivered_date,"
                + " date_pending_reason, retention_end_date, retention_years, object_key,"
                + " pii_masking_confirmed, uploaded_by_membership_id, uploaded_at) VALUES"
                + " (:contract, :revision, :type, :versionNo, :supersedes, :written, :delivered,"
                + " :pending, :retentionEnd, :years, :key, :masked, :by, :now) RETURNING id")
        .param("contract", d.contractId())
        .param("revision", d.contractRevisionId())
        .param("type", d.documentType())
        .param("versionNo", d.versionNo())
        .param("supersedes", d.supersedesDocumentId())
        .param("written", d.writtenDate())
        .param("delivered", d.deliveredDate())
        .param("pending", d.datePendingReason())
        .param("retentionEnd", d.retentionEndDate())
        .param("years", d.retentionYears())
        .param("key", d.objectKey())
        .param("masked", d.piiMaskingConfirmed())
        .param("by", d.uploadedBy())
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  public Optional<DocumentRow> find(Long contractId, Long documentId) {
    return jdbcClient
        .sql(
            "SELECT id, contract_id, contract_revision_id, document_type, version_no,"
                + " supersedes_document_id, written_date, delivered_date, date_pending_reason,"
                + " retention_end_date, retention_years, object_key, pii_masking_confirmed FROM"
                + " contract_document WHERE id = :id AND contract_id = :contract")
        .param("id", documentId)
        .param("contract", contractId)
        .query((rs, i) -> row(rs))
        .optional();
  }

  public boolean versionExists(Long contractId, String documentType, int versionNo) {
    return Boolean.TRUE.equals(
        jdbcClient
            .sql(
                "SELECT EXISTS (SELECT 1 FROM contract_document WHERE contract_id = :contract AND"
                    + " document_type = :type AND version_no = :versionNo)")
            .param("contract", contractId)
            .param("type", documentType)
            .param("versionNo", versionNo)
            .query(Boolean.class)
            .single());
  }

  private static DocumentRow row(ResultSet rs) throws SQLException {
    return new DocumentRow(
        rs.getLong("id"),
        rs.getLong("contract_id"),
        rs.getLong("contract_revision_id"),
        rs.getString("document_type"),
        rs.getInt("version_no"),
        rs.getObject("supersedes_document_id", Long.class),
        rs.getObject("written_date", LocalDate.class),
        rs.getObject("delivered_date", LocalDate.class),
        rs.getString("date_pending_reason"),
        rs.getObject("retention_end_date", LocalDate.class),
        rs.getInt("retention_years"),
        rs.getString("object_key"),
        rs.getBoolean("pii_masking_confirmed"));
  }
}
