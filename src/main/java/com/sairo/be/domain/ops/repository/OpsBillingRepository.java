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

/** 운영 콘솔의 청구서·환불 큐·PG 대사·증빙 발급 기록 조회와 쓰기다(OPS-06·14). */
@Profile("!migrate")
@Repository
public class OpsBillingRepository {

  public record InvoiceFilter(Long officeId, String status, LocalDate dueFrom, LocalDate dueTo) {}

  public record InvoiceRow(
      long id,
      long officeId,
      Long subscriptionId,
      Instant periodStart,
      Instant periodEnd,
      long amount,
      int memberCount,
      long unitPrice,
      long vatIncludedAmount,
      String priceVersion,
      String consentVersion,
      long consentAmountLimit,
      Instant consentedAt,
      Long purchaseConsentId,
      String status,
      Instant dueAt,
      Instant paidAt,
      Instant createdAt,
      Long supplyAmount,
      Long vatAmount) {}

  public record AttemptRow(
      int attemptNo,
      String pgOrderId,
      String result,
      Instant attemptedAt,
      Instant resolvedAt,
      String failureReason) {}

  public record InvoiceRefundRow(
      long id,
      String reasonCode,
      String status,
      Long refundAmount,
      Instant requestedAt,
      Instant resolvedAt) {}

  public record RefundRow(
      long id,
      long officeId,
      long invoiceId,
      String reasonCode,
      String reasonEventType,
      Long reasonEventId,
      Instant requestedAt,
      String status,
      Long refundAmount,
      Long entitlementAmount,
      long previousRefundAmount,
      long reservedAmount,
      Long reviewedByOperatorId,
      String reviewedByOperatorName,
      Instant reviewedAt,
      String rejectionReason,
      String pgRefundId,
      Instant resolvedAt,
      String failureReason) {}

  public record ReconciliationFilter(
      LocalDate businessDate, String status, Long assignedOperatorId, boolean unresolved) {}

  public record ReconciliationRow(
      long id,
      LocalDate businessDate,
      String sourceType,
      String pgOrderId,
      Long invoiceId,
      Long refundRequestId,
      Long officeId,
      Long internalAmount,
      Long pgAmount,
      String status,
      String differenceReason,
      String resolutionEvidence,
      Long assignedOperatorId,
      Instant resolvedAt,
      Instant createdAt) {}

  public record TaxFilter(
      String status, Long officeId, Long invoiceId, LocalDate dueFrom, LocalDate dueTo) {}

  public record TaxDocumentRow(
      long id,
      long invoiceId,
      long officeId,
      String officeName,
      String documentKind,
      boolean electronic,
      String status,
      String approvalNumber,
      LocalDate issuedOn,
      Long supplyAmount,
      Long vatAmount,
      Long originalDocumentId,
      String operatorNote,
      Instant recordedAt,
      Instant updatedAt,
      long version,
      LocalDate dueHint,
      boolean recipientRegistered) {}

  public record NewTaxDocument(
      long invoiceId,
      Long recipientId,
      String documentKind,
      boolean electronic,
      String status,
      String approvalNumber,
      LocalDate issuedOn,
      Long supplyAmount,
      Long vatAmount,
      Long originalDocumentId,
      String operatorNote,
      long operatorId,
      LocalDate retentionUntil,
      Instant now) {}

  private static final String INVOICE_COLUMNS =
      "SELECT i.id, i.office_id, i.subscription_id, i.billing_period_start, i.billing_period_end,"
          + " i.amount, i.member_count_snapshot, i.unit_price_snapshot, i.vat_included_amount,"
          + " i.price_version, i.consent_version, i.consent_amount_limit, i.consented_at,"
          + " i.purchase_consent_id, i.status, i.due_at, i.paid_at, i.created_at,"
          + " i.supply_amount, i.vat_amount FROM billing_invoice i";

  private static final String REFUND_COLUMNS =
      "SELECT r.id, r.office_id, r.invoice_id, r.reason_code, r.reason_event_type,"
          + " r.reason_event_id, r.requested_at, r.status, r.refund_amount, r.entitlement_amount,"
          + " r.previous_refund_amount, r.reserved_amount, r.reviewed_by_operator_id,"
          + " a.name AS reviewer_name, r.reviewed_at, r.rejection_reason, r.pg_refund_id,"
          + " r.resolved_at, r.failure_reason FROM refund_request r"
          + " LEFT JOIN operator_account a ON a.id = r.reviewed_by_operator_id";

  private static final String ENTRY_COLUMNS =
      "SELECT id, business_date, source_type, pg_order_id, invoice_id, refund_request_id,"
          + " office_id, internal_amount, pg_amount, status, difference_reason,"
          + " resolution_evidence, assigned_operator_id, resolved_at, created_at"
          + " FROM billing_reconciliation_entry";

  private static final String DUE_HINT =
      "(date_trunc('month', i.paid_at AT TIME ZONE 'Asia/Seoul') + interval '1 month'"
          + " + interval '9 days')::date";

  private static final String TAX_COLUMNS =
      "SELECT d.id, d.billing_invoice_id, i.office_id, o.name AS office_name, d.document_kind,"
          + " d.is_electronic, d.status, d.approval_number, d.issued_on, d.supply_amount,"
          + " d.vat_amount, d.original_document_id, d.operator_note, d.recorded_at,"
          + " d.updated_at, d.version, "
          + DUE_HINT
          + " AS due_hint, EXISTS (SELECT 1 FROM tax_invoice_recipient t WHERE t.office_id ="
          + " i.office_id AND t.status = 'ACTIVE') AS recipient_registered"
          + " FROM tax_document d JOIN billing_invoice i ON i.id = d.billing_invoice_id"
          + " JOIN office o ON o.id = i.office_id";

  private final JdbcClient jdbcClient;

  public OpsBillingRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  // 청구서

  public List<InvoiceRow> invoices(InvoiceFilter filter, int limit, long offset) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = invoiceWhere(filter, params);
    params.put("limit", limit);
    params.put("offset", offset);
    return jdbcClient
        .sql(
            INVOICE_COLUMNS
                + where
                + " ORDER BY i.due_at DESC, i.id DESC LIMIT :limit OFFSET :offset")
        .params(params)
        .query(OpsBillingRepository::mapInvoice)
        .list();
  }

  public long countInvoices(InvoiceFilter filter) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = invoiceWhere(filter, params);
    return jdbcClient
        .sql("SELECT count(*) FROM billing_invoice i" + where)
        .params(params)
        .query(Long.class)
        .single();
  }

  private static String invoiceWhere(InvoiceFilter filter, Map<String, Object> params) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    if (filter.officeId() != null) {
      where.append(" AND i.office_id = :officeId");
      params.put("officeId", filter.officeId());
    }
    if (filter.status() != null) {
      where.append(" AND i.status = :status");
      params.put("status", filter.status());
    }
    if (filter.dueFrom() != null) {
      where.append(" AND (i.due_at AT TIME ZONE 'Asia/Seoul')::date >= :dueFrom");
      params.put("dueFrom", filter.dueFrom());
    }
    if (filter.dueTo() != null) {
      where.append(" AND (i.due_at AT TIME ZONE 'Asia/Seoul')::date <= :dueTo");
      params.put("dueTo", filter.dueTo());
    }
    return where.toString();
  }

  public Optional<InvoiceRow> invoice(long invoiceId) {
    return jdbcClient
        .sql(INVOICE_COLUMNS + " WHERE i.id = :id")
        .param("id", invoiceId)
        .query(OpsBillingRepository::mapInvoice)
        .optional();
  }

  public Optional<InvoiceRow> lockInvoice(long invoiceId) {
    return jdbcClient
        .sql(INVOICE_COLUMNS + " WHERE i.id = :id FOR UPDATE")
        .param("id", invoiceId)
        .query(OpsBillingRepository::mapInvoice)
        .optional();
  }

  public List<AttemptRow> attempts(long invoiceId) {
    return jdbcClient
        .sql(
            "SELECT attempt_no, pg_order_id, result, attempted_at, resolved_at, failure_reason"
                + " FROM billing_charge_attempt WHERE invoice_id = :id ORDER BY attempt_no")
        .param("id", invoiceId)
        .query(
            (rs, n) ->
                new AttemptRow(
                    rs.getInt("attempt_no"),
                    rs.getString("pg_order_id"),
                    rs.getString("result"),
                    instant(rs, "attempted_at"),
                    instant(rs, "resolved_at"),
                    rs.getString("failure_reason")))
        .list();
  }

  public List<InvoiceRefundRow> invoiceRefunds(long invoiceId) {
    return jdbcClient
        .sql(
            "SELECT id, reason_code, status, refund_amount, requested_at, resolved_at"
                + " FROM refund_request WHERE invoice_id = :id ORDER BY requested_at, id")
        .param("id", invoiceId)
        .query(
            (rs, n) ->
                new InvoiceRefundRow(
                    rs.getLong("id"),
                    rs.getString("reason_code"),
                    rs.getString("status"),
                    rs.getObject("refund_amount", Long.class),
                    instant(rs, "requested_at"),
                    instant(rs, "resolved_at")))
        .list();
  }

  // 환불

  public List<RefundRow> refunds(String status, Long officeId, int limit, long offset) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = refundWhere(status, officeId, params);
    params.put("limit", limit);
    params.put("offset", offset);
    return jdbcClient
        .sql(
            REFUND_COLUMNS
                + where
                + " ORDER BY CASE WHEN r.status IN ('REJECTED','COMPLETED') THEN 1 ELSE 0 END,"
                + " r.requested_at, r.id LIMIT :limit OFFSET :offset")
        .params(params)
        .query(OpsBillingRepository::mapRefund)
        .list();
  }

  public long countRefunds(String status, Long officeId) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = refundWhere(status, officeId, params);
    return jdbcClient
        .sql("SELECT count(*) FROM refund_request r" + where)
        .params(params)
        .query(Long.class)
        .single();
  }

  private static String refundWhere(String status, Long officeId, Map<String, Object> params) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    if (status != null) {
      where.append(" AND r.status = :status");
      params.put("status", status);
    }
    if (officeId != null) {
      where.append(" AND r.office_id = :officeId");
      params.put("officeId", officeId);
    }
    return where.toString();
  }

  public Optional<RefundRow> refund(long refundId) {
    return jdbcClient
        .sql(REFUND_COLUMNS + " WHERE r.id = :id")
        .param("id", refundId)
        .query(OpsBillingRepository::mapRefund)
        .optional();
  }

  // 대사

  public List<ReconciliationRow> entries(ReconciliationFilter filter, int limit, long offset) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = entryWhere(filter, params);
    params.put("limit", limit);
    params.put("offset", offset);
    return jdbcClient
        .sql(ENTRY_COLUMNS + where + " ORDER BY id LIMIT :limit OFFSET :offset")
        .params(params)
        .query(OpsBillingRepository::mapEntry)
        .list();
  }

  public long countEntries(ReconciliationFilter filter) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = entryWhere(filter, params);
    return jdbcClient
        .sql("SELECT count(*) FROM billing_reconciliation_entry" + where)
        .params(params)
        .query(Long.class)
        .single();
  }

  private static String entryWhere(ReconciliationFilter filter, Map<String, Object> params) {
    StringBuilder where = new StringBuilder(" WHERE business_date = :businessDate");
    params.put("businessDate", filter.businessDate());
    if (filter.status() != null) {
      where.append(" AND status = :status");
      params.put("status", filter.status());
    }
    if (filter.assignedOperatorId() != null) {
      where.append(" AND assigned_operator_id = :assignee");
      params.put("assignee", filter.assignedOperatorId());
    }
    if (filter.unresolved()) {
      where.append(" AND resolved_at IS NULL AND status <> 'MATCHED'");
    }
    return where.toString();
  }

  public Optional<ReconciliationRow> lockEntry(long entryId) {
    return jdbcClient
        .sql(ENTRY_COLUMNS + " WHERE id = :id FOR UPDATE")
        .param("id", entryId)
        .query(OpsBillingRepository::mapEntry)
        .optional();
  }

  public void resolveEntry(
      long entryId, String reason, String evidence, long assigneeId, Instant now) {
    jdbcClient
        .sql(
            "UPDATE billing_reconciliation_entry SET difference_reason = :reason,"
                + " resolution_evidence = :evidence, assigned_operator_id = :assignee,"
                + " resolved_at = :now WHERE id = :id")
        .param("reason", reason)
        .param("evidence", evidence)
        .param("assignee", assigneeId)
        .param("now", Timestamp.from(now))
        .param("id", entryId)
        .update();
  }

  // 증빙

  public List<TaxDocumentRow> taxDocuments(TaxFilter filter, int limit, long offset) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = taxWhere(filter, params);
    params.put("limit", limit);
    params.put("offset", offset);
    return jdbcClient
        .sql(
            TAX_COLUMNS
                + where
                + " ORDER BY CASE d.status WHEN 'PENDING' THEN 0 WHEN 'ISSUE_FAILED' THEN 0"
                + " ELSE 1 END, "
                + DUE_HINT
                + " NULLS LAST, d.id LIMIT :limit OFFSET :offset")
        .params(params)
        .query(OpsBillingRepository::mapTax)
        .list();
  }

  public long countTaxDocuments(TaxFilter filter) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = taxWhere(filter, params);
    return jdbcClient
        .sql(
            "SELECT count(*) FROM tax_document d JOIN billing_invoice i ON i.id ="
                + " d.billing_invoice_id"
                + where)
        .params(params)
        .query(Long.class)
        .single();
  }

  private static String taxWhere(TaxFilter filter, Map<String, Object> params) {
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    if (filter.status() != null) {
      where.append(" AND d.status = :status");
      params.put("status", filter.status());
    }
    if (filter.officeId() != null) {
      where.append(" AND i.office_id = :officeId");
      params.put("officeId", filter.officeId());
    }
    if (filter.invoiceId() != null) {
      where.append(" AND d.billing_invoice_id = :invoiceId");
      params.put("invoiceId", filter.invoiceId());
    }
    if (filter.dueFrom() != null) {
      where.append(" AND ").append(DUE_HINT).append(" >= :dueFrom");
      params.put("dueFrom", filter.dueFrom());
    }
    if (filter.dueTo() != null) {
      where.append(" AND ").append(DUE_HINT).append(" <= :dueTo");
      params.put("dueTo", filter.dueTo());
    }
    return where.toString();
  }

  public Optional<TaxDocumentRow> taxDocument(long documentId) {
    return jdbcClient
        .sql(TAX_COLUMNS + " WHERE d.id = :id")
        .param("id", documentId)
        .query(OpsBillingRepository::mapTax)
        .optional();
  }

  public Optional<TaxDocumentRow> lockTaxDocument(long documentId) {
    return jdbcClient
        .sql(TAX_COLUMNS + " WHERE d.id = :id FOR UPDATE OF d")
        .param("id", documentId)
        .query(OpsBillingRepository::mapTax)
        .optional();
  }

  public boolean approvalNumberUsed(String approvalNumber) {
    return jdbcClient
            .sql("SELECT count(*) FROM tax_document WHERE approval_number = :number")
            .param("number", approvalNumber)
            .query(Long.class)
            .single()
        > 0;
  }

  public boolean hasIssued(long invoiceId, String kind) {
    return jdbcClient
            .sql(
                "SELECT count(*) FROM tax_document WHERE billing_invoice_id = :id AND"
                    + " document_kind = :kind AND status = 'ISSUED'")
            .param("id", invoiceId)
            .param("kind", kind)
            .query(Long.class)
            .single()
        > 0;
  }

  /** 같은 청구서·종류의 아직 끝나지 않은 기록(대기·발급 실패·발급 대상 아님)이다. 가장 최근 것 하나다. */
  public Optional<Long> openDocumentId(long invoiceId, String kind) {
    return jdbcClient
        .sql(
            "SELECT id FROM tax_document WHERE billing_invoice_id = :id AND document_kind = :kind"
                + " AND status IN ('PENDING','ISSUE_FAILED','NOT_REQUIRED')"
                + " ORDER BY id DESC LIMIT 1 FOR UPDATE")
        .param("id", invoiceId)
        .param("kind", kind)
        .query(Long.class)
        .optional();
  }

  public Optional<Long> activeRecipientId(long officeId) {
    return jdbcClient
        .sql(
            "SELECT id FROM tax_invoice_recipient WHERE office_id = :office AND status = 'ACTIVE'"
                + " ORDER BY id DESC LIMIT 1")
        .param("office", officeId)
        .query(Long.class)
        .optional();
  }

  public long insertTaxDocument(NewTaxDocument document) {
    return jdbcClient
        .sql(
            "INSERT INTO tax_document (billing_invoice_id, recipient_id, document_kind,"
                + " is_electronic, status, approval_number, issued_on, supply_amount, vat_amount,"
                + " original_document_id, operator_note, recorded_by_operator_id, recorded_at,"
                + " updated_at, retention_until) VALUES (:invoice, :recipient, :kind, :electronic,"
                + " :status, :approval, :issuedOn, :supply, :vat, :original, :note, :operator,"
                + " :now, :now, :retention) RETURNING id")
        .param("invoice", document.invoiceId())
        .param("recipient", document.recipientId())
        .param("kind", document.documentKind())
        .param("electronic", document.electronic())
        .param("status", document.status())
        .param("approval", document.approvalNumber())
        .param("issuedOn", date(document.issuedOn()))
        .param("supply", document.supplyAmount())
        .param("vat", document.vatAmount())
        .param("original", document.originalDocumentId())
        .param("note", document.operatorNote())
        .param("operator", document.operatorId())
        .param("now", Timestamp.from(document.now()))
        .param("retention", date(document.retentionUntil()))
        .query(Long.class)
        .single();
  }

  /** 끝나지 않은 기록을 새 결과로 바꾼다. 기록자와 기록 시각도 이번 기록으로 바꾸고 version을 올린다. */
  public void updateTaxDocument(long documentId, NewTaxDocument document) {
    jdbcClient
        .sql(
            "UPDATE tax_document SET recipient_id = :recipient, is_electronic = :electronic,"
                + " status = :status, approval_number = :approval, issued_on = :issuedOn,"
                + " supply_amount = :supply, vat_amount = :vat, operator_note = :note,"
                + " recorded_by_operator_id = :operator, recorded_at = :now, updated_at = :now,"
                + " retention_until = :retention, version = version + 1 WHERE id = :id")
        .param("recipient", document.recipientId())
        .param("electronic", document.electronic())
        .param("status", document.status())
        .param("approval", document.approvalNumber())
        .param("issuedOn", date(document.issuedOn()))
        .param("supply", document.supplyAmount())
        .param("vat", document.vatAmount())
        .param("note", document.operatorNote())
        .param("operator", document.operatorId())
        .param("now", Timestamp.from(document.now()))
        .param("retention", date(document.retentionUntil()))
        .param("id", documentId)
        .update();
  }

  // 매핑

  private static InvoiceRow mapInvoice(ResultSet rs, int n) throws SQLException {
    return new InvoiceRow(
        rs.getLong("id"),
        rs.getLong("office_id"),
        rs.getObject("subscription_id", Long.class),
        instant(rs, "billing_period_start"),
        instant(rs, "billing_period_end"),
        rs.getLong("amount"),
        rs.getInt("member_count_snapshot"),
        rs.getLong("unit_price_snapshot"),
        rs.getLong("vat_included_amount"),
        rs.getString("price_version"),
        rs.getString("consent_version"),
        rs.getLong("consent_amount_limit"),
        instant(rs, "consented_at"),
        rs.getObject("purchase_consent_id", Long.class),
        rs.getString("status"),
        instant(rs, "due_at"),
        instant(rs, "paid_at"),
        instant(rs, "created_at"),
        rs.getObject("supply_amount", Long.class),
        rs.getObject("vat_amount", Long.class));
  }

  private static RefundRow mapRefund(ResultSet rs, int n) throws SQLException {
    return new RefundRow(
        rs.getLong("id"),
        rs.getLong("office_id"),
        rs.getLong("invoice_id"),
        rs.getString("reason_code"),
        rs.getString("reason_event_type"),
        rs.getObject("reason_event_id", Long.class),
        instant(rs, "requested_at"),
        rs.getString("status"),
        rs.getObject("refund_amount", Long.class),
        rs.getObject("entitlement_amount", Long.class),
        rs.getLong("previous_refund_amount"),
        rs.getLong("reserved_amount"),
        rs.getObject("reviewed_by_operator_id", Long.class),
        rs.getString("reviewer_name"),
        instant(rs, "reviewed_at"),
        rs.getString("rejection_reason"),
        rs.getString("pg_refund_id"),
        instant(rs, "resolved_at"),
        rs.getString("failure_reason"));
  }

  private static ReconciliationRow mapEntry(ResultSet rs, int n) throws SQLException {
    return new ReconciliationRow(
        rs.getLong("id"),
        rs.getObject("business_date", LocalDate.class),
        rs.getString("source_type"),
        rs.getString("pg_order_id"),
        rs.getObject("invoice_id", Long.class),
        rs.getObject("refund_request_id", Long.class),
        rs.getObject("office_id", Long.class),
        rs.getObject("internal_amount", Long.class),
        rs.getObject("pg_amount", Long.class),
        rs.getString("status"),
        rs.getString("difference_reason"),
        rs.getString("resolution_evidence"),
        rs.getObject("assigned_operator_id", Long.class),
        instant(rs, "resolved_at"),
        instant(rs, "created_at"));
  }

  private static TaxDocumentRow mapTax(ResultSet rs, int n) throws SQLException {
    return new TaxDocumentRow(
        rs.getLong("id"),
        rs.getLong("billing_invoice_id"),
        rs.getLong("office_id"),
        rs.getString("office_name"),
        rs.getString("document_kind"),
        rs.getBoolean("is_electronic"),
        rs.getString("status"),
        rs.getString("approval_number"),
        rs.getObject("issued_on", LocalDate.class),
        rs.getObject("supply_amount", Long.class),
        rs.getObject("vat_amount", Long.class),
        rs.getObject("original_document_id", Long.class),
        rs.getString("operator_note"),
        instant(rs, "recorded_at"),
        instant(rs, "updated_at"),
        rs.getLong("version"),
        rs.getObject("due_hint", LocalDate.class),
        rs.getBoolean("recipient_registered"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static Date date(LocalDate value) {
    return value == null ? null : Date.valueOf(value);
  }
}
