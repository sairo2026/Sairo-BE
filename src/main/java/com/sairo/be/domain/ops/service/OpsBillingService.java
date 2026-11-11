package com.sairo.be.domain.ops.service;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.billing.entity.RefundRequest;
import com.sairo.be.domain.billing.service.RefundProcessingService;
import com.sairo.be.domain.billing.service.RefundProcessingService.ReviewCommand;
import com.sairo.be.domain.legal.service.RetentionRuleLookup;
import com.sairo.be.domain.ops.dto.request.OpsBillingRequests.ReconciliationResolveRequest;
import com.sairo.be.domain.ops.dto.request.OpsBillingRequests.RefundReviewRequest;
import com.sairo.be.domain.ops.dto.request.OpsBillingRequests.TaxDocumentAmendRequest;
import com.sairo.be.domain.ops.dto.request.OpsBillingRequests.TaxDocumentRecordRequest;
import com.sairo.be.domain.ops.dto.response.OpsBillingResponses.ChargeAttempt;
import com.sairo.be.domain.ops.dto.response.OpsBillingResponses.InvoiceDetail;
import com.sairo.be.domain.ops.dto.response.OpsBillingResponses.InvoiceItem;
import com.sairo.be.domain.ops.dto.response.OpsBillingResponses.InvoiceRefund;
import com.sairo.be.domain.ops.dto.response.OpsBillingResponses.ReconciliationItem;
import com.sairo.be.domain.ops.dto.response.OpsBillingResponses.ReconciliationResolveResponse;
import com.sairo.be.domain.ops.dto.response.OpsBillingResponses.RefundDetail;
import com.sairo.be.domain.ops.dto.response.OpsBillingResponses.RefundExecuteResponse;
import com.sairo.be.domain.ops.dto.response.OpsBillingResponses.RefundItem;
import com.sairo.be.domain.ops.dto.response.OpsBillingResponses.RefundReviewResponse;
import com.sairo.be.domain.ops.dto.response.OpsBillingResponses.TaxDocumentItem;
import com.sairo.be.domain.ops.dto.response.OpsBillingResponses.TaxDocumentResponse;
import com.sairo.be.domain.ops.repository.OperatorAccountRepository;
import com.sairo.be.domain.ops.repository.OpsAuditLogRepository;
import com.sairo.be.domain.ops.repository.OpsBillingRepository;
import com.sairo.be.domain.ops.repository.OpsBillingRepository.InvoiceFilter;
import com.sairo.be.domain.ops.repository.OpsBillingRepository.InvoiceRow;
import com.sairo.be.domain.ops.repository.OpsBillingRepository.NewTaxDocument;
import com.sairo.be.domain.ops.repository.OpsBillingRepository.ReconciliationFilter;
import com.sairo.be.domain.ops.repository.OpsBillingRepository.ReconciliationRow;
import com.sairo.be.domain.ops.repository.OpsBillingRepository.RefundRow;
import com.sairo.be.domain.ops.repository.OpsBillingRepository.TaxDocumentRow;
import com.sairo.be.domain.ops.repository.OpsBillingRepository.TaxFilter;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.security.OperatorStepUpState;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import com.sairo.be.global.support.TimeRules;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제담당의 청구서 조회, 환불 검토·PG 취소 실행, PG 거래 대사, 세금계산서·현금영수증 발급 기록이다(OPS-06·14). 환불 산정과 PG 호출은 billing의
 * RefundProcessingService가 맡고 이 서비스는 역할·재인증·감사 기록을 붙인다.
 */
@Profile("!migrate")
@Service
public class OpsBillingService {

  static final Set<String> INVOICE_STATUSES =
      Set.of(
          "UNBILLED",
          "PROCESSING",
          "VERIFYING",
          "PAID",
          "FAILED",
          "PARTIALLY_REFUNDED",
          "FULLY_REFUNDED",
          "CANCELLED");
  static final Set<String> REFUND_STATUSES =
      Set.of(
          "RECEIVED",
          "REVIEWING",
          "APPROVED",
          "REJECTED",
          "PROCESSING",
          "VERIFYING",
          "COMPLETED",
          "FAILED");
  static final Set<String> ENTRY_STATUSES =
      Set.of("MATCHED", "AMOUNT_MISMATCH", "PG_ONLY", "INTERNAL_ONLY", "VERIFYING");
  static final Set<String> TAX_STATUSES =
      Set.of("NOT_REQUIRED", "PENDING", "ISSUED", "ISSUE_FAILED", "AMENDED", "CANCELLED");
  static final Set<String> TAX_ELIGIBLE_INVOICE =
      Set.of("PAID", "PARTIALLY_REFUNDED", "FULLY_REFUNDED");
  static final int TAX_RETENTION_DEFAULT_DAYS = 1825;

  private final OpsBillingRepository billing;
  private final RefundProcessingService refunds;
  private final OperatorAccountRepository accounts;
  private final OpsAuditLogRepository auditLog;
  private final RetentionRuleLookup retentionRules;
  private final OperatorStepUpState stepUp;
  private final Clock clock;

  public OpsBillingService(
      OpsBillingRepository billing,
      RefundProcessingService refunds,
      OperatorAccountRepository accounts,
      OpsAuditLogRepository auditLog,
      RetentionRuleLookup retentionRules,
      OperatorStepUpState stepUp,
      Clock clock) {
    this.billing = billing;
    this.refunds = refunds;
    this.accounts = accounts;
    this.auditLog = auditLog;
    this.retentionRules = retentionRules;
    this.stepUp = stepUp;
    this.clock = clock;
  }

  // 청구서

  @Transactional(readOnly = true)
  public PageResponse<InvoiceItem> invoices(
      Long officeId, String status, String dueFrom, String dueTo, PageQuery page) {
    InvoiceFilter filter =
        new InvoiceFilter(
            OpsQueryParams.positiveId("officeId", officeId),
            OpsQueryParams.oneOf("status", status, INVOICE_STATUSES),
            date("dueFrom", dueFrom),
            date("dueTo", dueTo));
    requireOrder(filter.dueFrom(), filter.dueTo(), "dueTo");
    List<InvoiceItem> items =
        billing.invoices(filter, page.size(), offset(page)).stream()
            .map(OpsBillingService::toInvoiceItem)
            .toList();
    return PageResponse.of(items, page.page(), page.size(), billing.countInvoices(filter));
  }

  @Transactional(readOnly = true)
  public InvoiceDetail invoice(long invoiceId) {
    InvoiceRow row = billing.invoice(invoiceId).orElseThrow(OpsBillingService::notFound);
    return new InvoiceDetail(
        row.id(),
        row.officeId(),
        row.subscriptionId(),
        toDisplayOffset(row.periodStart()),
        toDisplayOffset(row.periodEnd()),
        row.amount(),
        row.memberCount(),
        row.unitPrice(),
        row.vatIncludedAmount(),
        row.priceVersion(),
        row.consentVersion(),
        row.consentAmountLimit(),
        toDisplayOffset(row.consentedAt()),
        row.purchaseConsentId(),
        row.status(),
        toDisplayOffset(row.dueAt()),
        toDisplayOffset(row.paidAt()),
        toDisplayOffset(row.createdAt()),
        billing.attempts(invoiceId).stream()
            .map(
                attempt ->
                    new ChargeAttempt(
                        attempt.attemptNo(),
                        attempt.pgOrderId(),
                        attempt.result(),
                        toDisplayOffset(attempt.attemptedAt()),
                        toDisplayOffset(attempt.resolvedAt()),
                        "FAILED".equals(attempt.result()) ? attempt.failureReason() : null))
            .toList(),
        billing.invoiceRefunds(invoiceId).stream()
            .map(
                refund ->
                    new InvoiceRefund(
                        refund.id(),
                        refund.reasonCode(),
                        refund.status(),
                        refund.refundAmount(),
                        toDisplayOffset(refund.requestedAt()),
                        toDisplayOffset(refund.resolvedAt())))
            .toList());
  }

  // 환불

  @Transactional(readOnly = true)
  public PageResponse<RefundItem> refundQueue(String status, Long officeId, PageQuery page) {
    String resolvedStatus = OpsQueryParams.oneOf("status", status, REFUND_STATUSES);
    Long resolvedOffice = OpsQueryParams.positiveId("officeId", officeId);
    List<RefundItem> items =
        billing.refunds(resolvedStatus, resolvedOffice, page.size(), offset(page)).stream()
            .map(
                row ->
                    new RefundItem(
                        row.id(),
                        row.officeId(),
                        row.invoiceId(),
                        row.reasonCode(),
                        toDisplayOffset(row.requestedAt()),
                        row.status(),
                        row.refundAmount(),
                        row.entitlementAmount(),
                        row.previousRefundAmount(),
                        row.reservedAmount(),
                        toDisplayOffset(row.reviewedAt()),
                        toDisplayOffset(row.resolvedAt()),
                        row.rejectionReason()))
            .toList();
    return PageResponse.of(
        items, page.page(), page.size(), billing.countRefunds(resolvedStatus, resolvedOffice));
  }

  @Transactional(readOnly = true)
  public RefundDetail refund(long refundId) {
    RefundRow row = billing.refund(refundId).orElseThrow(OpsBillingService::notFound);
    return new RefundDetail(
        row.id(),
        row.officeId(),
        row.invoiceId(),
        row.reasonCode(),
        row.reasonEventType(),
        row.reasonEventId() == null ? null : String.valueOf(row.reasonEventId()),
        toDisplayOffset(row.requestedAt()),
        row.status(),
        row.entitlementAmount(),
        row.previousRefundAmount(),
        row.reservedAmount(),
        row.refundAmount(),
        row.reviewedByOperatorId(),
        row.reviewedByOperatorName(),
        toDisplayOffset(row.reviewedAt()),
        row.rejectionReason(),
        maskReference(row.pgRefundId()),
        toDisplayOffset(row.resolvedAt()));
  }

  @Transactional
  public RefundReviewResponse review(long operatorId, long refundId, RefundReviewRequest body) {
    boolean approve = body.decision().equals("APPROVE");
    String note = blankToNull(body.note());
    if (!approve && note == null) {
      throw OpsQueryParams.invalid("note", FieldErrorCode.REQUIRED, "거절하려면 사유를 적어야 합니다.");
    }
    Long eventId = body.reasonEventId() == null ? null : Long.valueOf(body.reasonEventId());
    RefundRequest refund =
        refunds.review(refundId, operatorId, new ReviewCommand(approve, note, eventId));
    auditLog.insert(
        operatorId,
        approve ? "REFUND_APPROVED" : "REFUND_REJECTED",
        "REFUND_REQUEST",
        refundId,
        note == null ? refund.getReasonCode().name() : refund.getReasonCode().name() + ": " + note,
        refund.getReviewedAt());
    return new RefundReviewResponse(
        refund.getId(),
        refund.getStatus().name(),
        toDisplayOffset(refund.getReviewedAt()),
        refund.getRejectionReason(),
        refund.getRefundAmount(),
        refund.getEntitlementAmount(),
        refund.getPreviousRefundAmount(),
        refund.getReservedAmount());
  }

  /** 확인 중인 환불은 같은 멱등키로 다시 요청해 결과를 확정한다. PG 호출이 끼어 있어 트랜잭션을 나눈다. */
  public RefundExecuteResponse execute(long operatorId, long refundId, HttpSession session) {
    refunds.find(refundId);
    stepUp.require(session);
    RefundRequest refund = refunds.execute(refundId, operatorId);
    auditLog.insert(
        operatorId,
        "REFUND_EXECUTED",
        "REFUND_REQUEST",
        refundId,
        refund.getStatus().name() + " " + refund.getRefundAmount(),
        clock.instant());
    return new RefundExecuteResponse(
        refund.getId(),
        refund.getStatus().name(),
        refund.getStatus().name().equals("FAILED") ? refund.getFailureReason() : null);
  }

  // 대사

  @Transactional(readOnly = true)
  public PageResponse<ReconciliationItem> reconciliation(
      String businessDate,
      String status,
      Long assignedOperatorId,
      Boolean unresolved,
      PageQuery page) {
    LocalDate date = date("businessDate", businessDate);
    ReconciliationFilter filter =
        new ReconciliationFilter(
            date == null ? TimeRules.displayDate(clock.instant()).minusDays(1) : date,
            OpsQueryParams.oneOf("status", status, ENTRY_STATUSES),
            OpsQueryParams.positiveId("assignedOperatorId", assignedOperatorId),
            Boolean.TRUE.equals(unresolved));
    List<ReconciliationItem> items =
        billing.entries(filter, page.size(), offset(page)).stream()
            .map(OpsBillingService::toEntryItem)
            .toList();
    return PageResponse.of(items, page.page(), page.size(), billing.countEntries(filter));
  }

  @Transactional
  public ReconciliationResolveResponse resolve(
      long operatorId, long entryId, ReconciliationResolveRequest body) {
    ReconciliationRow entry = billing.lockEntry(entryId).orElseThrow(OpsBillingService::notFound);
    if (entry.status().equals("MATCHED") || entry.resolvedAt() != null) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    long assignee = body.assigneeOperatorId() == null ? operatorId : body.assigneeOperatorId();
    if (!accounts.isActiveWithRole(assignee, OperatorRole.BILLING)) {
      throw new BusinessException(ErrorCode.ASSIGNEE_NOT_ELIGIBLE);
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    String reason = body.differenceReason().strip();
    String evidence = body.resolutionEvidence().strip();
    billing.resolveEntry(entryId, reason, evidence, assignee, now);
    auditLog.insert(
        operatorId, "RECONCILIATION_RESOLVED", "RECONCILIATION_ENTRY", entryId, reason, now);
    return new ReconciliationResolveResponse(
        entryId, entry.status(), reason, evidence, assignee, toDisplayOffset(now));
  }

  // 증빙

  @Transactional(readOnly = true)
  public PageResponse<TaxDocumentItem> taxDocuments(
      String status, Long officeId, Long invoiceId, String dueFrom, String dueTo, PageQuery page) {
    TaxFilter filter =
        new TaxFilter(
            OpsQueryParams.oneOf("status", status, TAX_STATUSES),
            OpsQueryParams.positiveId("officeId", officeId),
            OpsQueryParams.positiveId("invoiceId", invoiceId),
            date("dueFrom", dueFrom),
            date("dueTo", dueTo));
    requireOrder(filter.dueFrom(), filter.dueTo(), "dueTo");
    List<TaxDocumentItem> items =
        billing.taxDocuments(filter, page.size(), offset(page)).stream()
            .map(
                row ->
                    new TaxDocumentItem(
                        row.id(),
                        row.invoiceId(),
                        row.officeId(),
                        row.officeName(),
                        row.documentKind(),
                        row.electronic(),
                        row.status(),
                        row.approvalNumber(),
                        row.issuedOn(),
                        row.supplyAmount(),
                        row.vatAmount(),
                        row.dueHint(),
                        row.recipientRegistered(),
                        row.originalDocumentId()))
            .toList();
    return PageResponse.of(items, page.page(), page.size(), billing.countTaxDocuments(filter));
  }

  /**
   * 같은 청구서·종류의 끝나지 않은 기록(대기·발급 실패·발급 대상 아님)이 있으면 그 행을 새 결과로 바꾸고, 없으면 새 행을 만든다. 발급됨이 이미 있으면 409다.
   */
  @Transactional
  public TaxDocumentResponse recordTaxDocument(
      long operatorId, long invoiceId, TaxDocumentRecordRequest body) {
    String status = body.status();
    String note = blankToNull(body.operatorNote());
    String approval = blankToNull(body.approvalNumber());
    boolean issued = status.equals("ISSUED");
    InvoiceRow invoice = billing.lockInvoice(invoiceId).orElseThrow(OpsBillingService::notFound);
    if (issued) {
      requireField("approvalNumber", approval);
      requireField("issuedOn", body.issuedOn());
      requireField("supplyAmount", body.supplyAmount());
      requireField("vatAmount", body.vatAmount());
      long expectedSupply =
          invoice.supplyAmount() != null ? invoice.supplyAmount() : body.supplyAmount();
      if (body.supplyAmount() != expectedSupply) {
        throw OpsQueryParams.invalid(
            "supplyAmount", FieldErrorCode.NOT_ALLOWED, "공급가액이 청구서의 공급가액과 다릅니다.");
      }
      if (body.supplyAmount() + body.vatAmount() != invoice.amount()) {
        throw OpsQueryParams.invalid(
            "vatAmount", FieldErrorCode.NOT_ALLOWED, "공급가액과 세액의 합이 청구서 총액과 다릅니다.");
      }
    } else if (approval != null
        || body.issuedOn() != null
        || body.supplyAmount() != null
        || body.vatAmount() != null) {
      throw OpsQueryParams.invalid(
          approval != null ? "approvalNumber" : "issuedOn",
          FieldErrorCode.NOT_ALLOWED,
          "발급됨이 아닌 기록에는 승인번호·발급일·금액을 넣지 않습니다.");
    }
    if (status.equals("ISSUE_FAILED")) {
      requireField("operatorNote", note);
    }
    if (!TAX_ELIGIBLE_INVOICE.contains(invoice.status())) {
      throw new BusinessException(ErrorCode.TAX_INVOICE_NOT_ELIGIBLE);
    }
    if (billing.hasIssued(invoiceId, body.documentKind())) {
      throw new BusinessException(ErrorCode.TAX_DOCUMENT_ALREADY_ISSUED);
    }
    if (issued && billing.approvalNumberUsed(approval)) {
      throw duplicateApproval();
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    NewTaxDocument document =
        new NewTaxDocument(
            invoiceId,
            billing.activeRecipientId(invoice.officeId()).orElse(null),
            body.documentKind(),
            body.isElectronic(),
            status,
            issued ? approval : null,
            issued ? body.issuedOn() : null,
            issued ? body.supplyAmount() : null,
            issued ? body.vatAmount() : null,
            null,
            note,
            operatorId,
            issued ? retentionUntil(body.issuedOn()) : null,
            now);
    long documentId =
        billing
            .openDocumentId(invoiceId, body.documentKind())
            .map(
                existing -> {
                  billing.updateTaxDocument(existing, document);
                  return existing;
                })
            .orElseGet(() -> billing.insertTaxDocument(document));
    auditLog.insert(
        operatorId,
        "TAX_DOCUMENT_RECORDED",
        "TAX_DOCUMENT",
        documentId,
        body.documentKind() + " " + status + " 청구서 " + invoiceId,
        now);
    return toTaxResponse(billing.taxDocument(documentId).orElseThrow());
  }

  @Transactional
  public TaxDocumentResponse amendTaxDocument(
      long operatorId, long documentId, TaxDocumentAmendRequest body) {
    TaxDocumentRow original =
        billing.lockTaxDocument(documentId).orElseThrow(OpsBillingService::notFound);
    if (!original.status().equals("ISSUED")) {
      throw new BusinessException(ErrorCode.TAX_INVOICE_NOT_ELIGIBLE);
    }
    String amendApproval = body.approvalNumber().strip();
    if (billing.approvalNumberUsed(amendApproval)) {
      throw duplicateApproval();
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    String note = body.operatorNote().strip();
    long amendedId =
        billing.insertTaxDocument(
            new NewTaxDocument(
                original.invoiceId(),
                billing.activeRecipientId(original.officeId()).orElse(null),
                original.documentKind(),
                original.electronic(),
                "AMENDED",
                amendApproval,
                body.issuedOn(),
                body.supplyAmount(),
                body.vatAmount(),
                original.id(),
                note,
                operatorId,
                retentionUntil(body.issuedOn()),
                now));
    auditLog.insert(
        operatorId,
        "TAX_DOCUMENT_AMENDED",
        "TAX_DOCUMENT",
        amendedId,
        "원본 " + original.id() + ": " + note,
        now);
    return toTaxResponse(billing.taxDocument(amendedId).orElseThrow());
  }

  private LocalDate retentionUntil(LocalDate issuedOn) {
    return issuedOn.plusDays(
        retentionRules.retentionDays("TAX_DOCUMENT").orElse(TAX_RETENTION_DEFAULT_DAYS));
  }

  // 공통

  private static InvoiceItem toInvoiceItem(InvoiceRow row) {
    return new InvoiceItem(
        row.id(),
        row.officeId(),
        row.subscriptionId(),
        toDisplayOffset(row.periodStart()),
        toDisplayOffset(row.periodEnd()),
        row.amount(),
        row.memberCount(),
        row.unitPrice(),
        row.vatIncludedAmount(),
        row.priceVersion(),
        row.consentVersion(),
        row.consentAmountLimit(),
        toDisplayOffset(row.consentedAt()),
        row.purchaseConsentId(),
        row.status(),
        toDisplayOffset(row.dueAt()),
        toDisplayOffset(row.paidAt()),
        toDisplayOffset(row.createdAt()));
  }

  private static ReconciliationItem toEntryItem(ReconciliationRow row) {
    return new ReconciliationItem(
        row.id(),
        row.businessDate(),
        row.sourceType(),
        row.pgOrderId(),
        row.invoiceId(),
        row.refundRequestId(),
        row.officeId(),
        row.internalAmount(),
        row.pgAmount(),
        row.status(),
        row.differenceReason(),
        row.resolutionEvidence(),
        row.assignedOperatorId(),
        toDisplayOffset(row.resolvedAt()),
        toDisplayOffset(row.createdAt()));
  }

  private static TaxDocumentResponse toTaxResponse(TaxDocumentRow row) {
    return new TaxDocumentResponse(
        row.id(),
        row.invoiceId(),
        row.documentKind(),
        row.electronic(),
        row.status(),
        row.approvalNumber(),
        row.issuedOn(),
        row.supplyAmount(),
        row.vatAmount(),
        row.originalDocumentId(),
        row.operatorNote(),
        toDisplayOffset(row.recordedAt()),
        toDisplayOffset(row.updatedAt()),
        row.version());
  }

  /** PG 참조 번호는 앞 4자리만 남긴다. */
  static String maskReference(String reference) {
    if (reference == null) {
      return null;
    }
    if (reference.length() <= 4) {
      return "*".repeat(reference.length());
    }
    return reference.substring(0, 4) + "*".repeat(reference.length() - 4);
  }

  private static LocalDate date(String field, String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(raw.strip());
    } catch (DateTimeParseException e) {
      throw OpsQueryParams.invalid(
          field, FieldErrorCode.INVALID_FORMAT, field + "는 YYYY-MM-DD 날짜여야 합니다.");
    }
  }

  private static void requireOrder(LocalDate from, LocalDate to, String field) {
    if (from != null && to != null && to.isBefore(from)) {
      throw OpsQueryParams.invalid(field, FieldErrorCode.OUT_OF_RANGE, "기간의 끝이 시작보다 앞섭니다.");
    }
  }

  private static void requireField(String field, Object value) {
    if (value == null) {
      throw OpsQueryParams.invalid(field, FieldErrorCode.REQUIRED, "발급됨이면 " + field + "가 필요합니다.");
    }
  }

  private static long offset(PageQuery page) {
    return (long) page.page() * page.size();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  /** 승인번호는 발급 서비스가 부여하는 고유 값이라 이미 기록된 번호는 받지 않는다. */
  private static BusinessException duplicateApproval() {
    return OpsQueryParams.invalid("approvalNumber", FieldErrorCode.NOT_ALLOWED, "이미 기록된 승인번호입니다.");
  }

  private static BusinessException notFound() {
    return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
  }
}
