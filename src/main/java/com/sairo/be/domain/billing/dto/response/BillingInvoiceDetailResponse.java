package com.sairo.be.domain.billing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "청구서 상세 응답. 결제 시도 이력과 증빙 발급 상태를 포함한다.")
public record BillingInvoiceDetailResponse(
    Long invoiceId,
    OffsetDateTime periodStart,
    OffsetDateTime periodEnd,
    long amount,
    Long supplyAmount,
    Long vatAmount,
    Integer vatRateBp,
    int memberCount,
    long consentAmountLimit,
    String status,
    long refundedAmount,
    long refundableAmount,
    OffsetDateTime nextRetryAt,
    List<Attempt> attempts,
    Supplier supplier,
    List<TaxDocument> taxDocuments) {

  public record Attempt(
      String pgOrderId,
      String pgTransactionId,
      String result,
      String failureCategory,
      OffsetDateTime attemptedAt) {}

  public record Supplier(
      String businessName,
      String representativeName,
      String businessNumber,
      String address,
      Integer infoVersion) {}

  public record TaxDocument(
      Long documentId,
      String documentKind,
      String status,
      LocalDate issuedOn,
      String maskedReceiverEmail,
      Long supplyAmount,
      Long vatAmount) {}
}
