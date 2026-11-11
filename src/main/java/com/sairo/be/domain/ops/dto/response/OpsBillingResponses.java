package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 운영 콘솔 청구서·환불·PG 대사·증빙 발급 응답이다(OPS-06·14). 카드번호·결제키는 담지 않는다. */
public final class OpsBillingResponses {

  private OpsBillingResponses() {}

  @Schema(description = "청구서 목록 항목")
  public record InvoiceItem(
      Long invoiceId,
      Long officeId,
      Long subscriptionId,
      OffsetDateTime billingPeriodStart,
      OffsetDateTime billingPeriodEnd,
      long amount,
      int memberCountSnapshot,
      long unitPriceSnapshot,
      long vatIncludedAmount,
      String priceVersion,
      String consentVersion,
      long consentAmountLimit,
      OffsetDateTime consentedAt,
      Long purchaseConsentId,
      String status,
      OffsetDateTime dueAt,
      OffsetDateTime paidAt,
      OffsetDateTime createdAt) {}

  @Schema(description = "청구서 상세. 결제 시도별 주문 ID와 연결 환불을 담는다")
  public record InvoiceDetail(
      Long invoiceId,
      Long officeId,
      Long subscriptionId,
      OffsetDateTime billingPeriodStart,
      OffsetDateTime billingPeriodEnd,
      long amount,
      int memberCountSnapshot,
      long unitPriceSnapshot,
      long vatIncludedAmount,
      String priceVersion,
      String consentVersion,
      long consentAmountLimit,
      OffsetDateTime consentedAt,
      Long purchaseConsentId,
      String status,
      OffsetDateTime dueAt,
      OffsetDateTime paidAt,
      OffsetDateTime createdAt,
      List<ChargeAttempt> chargeAttempts,
      List<InvoiceRefund> refunds) {}

  @Schema(description = "결제 시도")
  public record ChargeAttempt(
      int attemptNo,
      String pgOrderId,
      String result,
      OffsetDateTime attemptedAt,
      OffsetDateTime resolvedAt,
      String failureReason) {}

  @Schema(description = "청구서에 연결된 환불 사건")
  public record InvoiceRefund(
      Long refundId,
      String reasonCode,
      String status,
      Long refundAmount,
      OffsetDateTime requestedAt,
      OffsetDateTime resolvedAt) {}

  @Schema(description = "환불 신청 큐 항목")
  public record RefundItem(
      Long refundId,
      Long officeId,
      Long invoiceId,
      String reasonCode,
      OffsetDateTime requestedAt,
      String status,
      Long refundAmount,
      Long entitlementAmount,
      long previousRefundAmount,
      long reservedAmount,
      OffsetDateTime reviewedAt,
      OffsetDateTime resolvedAt,
      String rejectionReason) {}

  @Schema(description = "환불 사건 상세. PG 환불 참조 번호는 앞 4자리만 보인다")
  public record RefundDetail(
      Long refundId,
      Long officeId,
      Long invoiceId,
      String reasonCode,
      String reasonEventType,
      String reasonEventId,
      OffsetDateTime requestedAt,
      String status,
      Long entitlementAmount,
      long previousRefundAmount,
      long reservedAmount,
      Long refundAmount,
      Long reviewedByOperatorId,
      String reviewedByOperatorName,
      OffsetDateTime reviewedAt,
      String rejectionReason,
      String pgRefundId,
      OffsetDateTime resolvedAt) {}

  @Schema(description = "환불 검토 결과")
  public record RefundReviewResponse(
      Long refundId,
      String status,
      OffsetDateTime reviewedAt,
      String rejectionReason,
      Long refundAmount,
      Long entitlementAmount,
      long previousRefundAmount,
      long reservedAmount) {}

  @Schema(description = "PG 취소 실행 결과. 불확실하면 VERIFYING, 확정 성공이면 COMPLETED, 확정 실패면 FAILED")
  public record RefundExecuteResponse(Long refundId, String status, String failureReason) {}

  @Schema(description = "PG 거래 대사 항목")
  public record ReconciliationItem(
      Long entryId,
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
      OffsetDateTime resolvedAt,
      OffsetDateTime createdAt) {}

  @Schema(description = "대사 불일치 해결 기록 결과")
  public record ReconciliationResolveResponse(
      Long entryId,
      String status,
      String differenceReason,
      String resolutionEvidence,
      Long assignedOperatorId,
      OffsetDateTime resolvedAt) {}

  @Schema(description = "증빙 발급 상태 목록 항목. 발급 기한 힌트는 세무사 확인 전 가정이다")
  public record TaxDocumentItem(
      Long documentId,
      Long invoiceId,
      Long officeId,
      String officeName,
      String documentKind,
      boolean isElectronic,
      String status,
      String approvalNumber,
      LocalDate issuedOn,
      Long supplyAmount,
      Long vatAmount,
      LocalDate dueHint,
      boolean recipientRegistered,
      Long originalDocumentId) {}

  @Schema(description = "증빙 발급 기록")
  public record TaxDocumentResponse(
      Long documentId,
      Long invoiceId,
      String documentKind,
      boolean isElectronic,
      String status,
      String approvalNumber,
      LocalDate issuedOn,
      Long supplyAmount,
      Long vatAmount,
      Long originalDocumentId,
      String operatorNote,
      OffsetDateTime recordedAt,
      OffsetDateTime updatedAt,
      long version) {}
}
