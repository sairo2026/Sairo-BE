package com.sairo.be.domain.contract.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "중개보수 청구 등록 결과(ContractFeeResponse)다. version은 부모 계약의 현재 버전이다.")
public record ContractFeeResponse(
    Long feeId, Long contractId, Long payerPartyId, long totalAmount, Long officeId, long version) {

  @Schema(description = "약정보수 조정·정정 결과(ContractFeeRevisionResponse)다.")
  public record FeeRevisionResult(
      Long revisionId,
      Long feeId,
      @Schema(allowableValues = {"INITIAL", "ADJUSTED", "CORRECTED"}) String revisionType,
      long totalAmount,
      Long officeId,
      long version) {}

  @Schema(description = "중개보수 수납·환급·정정 기록 결과(ContractFeeTransactionResponse)다.")
  public record FeeTransactionResult(
      Long transactionId,
      Long feeId,
      @Schema(allowableValues = {"RECEIPT", "REFUND", "ADJUSTMENT"}) String transactionType,
      long amount,
      LocalDate actualDate,
      Long officeId,
      long version) {}

  @Schema(description = "계약의 중개보수 내역과 지급의무자별 합계(ContractFeeListResponse)다. 계약대금은 담지 않는다.")
  public record FeeList(List<FeeItem> items, List<FeePayer> payers) {}

  @Schema(description = "중개보수 청구 한 건이다. 금액은 오늘 효력이 있는 대체되지 않은 최신 사건 기준이다.")
  public record FeeItem(
      Long feeId,
      @Schema(allowableValues = {"SELLER_LANDLORD", "BUYER_TENANT"}) String payerType,
      Long payerPartyId,
      String payerName,
      long agreedFeeAmount,
      boolean vatIncluded,
      long supplyAmount,
      long taxAmount,
      long totalAmount,
      OffsetDateTime billedAt,
      @Schema(nullable = true) OffsetDateTime dueAt,
      long netReceivedAmount,
      List<FeeRevisionItem> revisions,
      List<FeeTransactionItem> transactions,
      Long officeId,
      long version) {}

  @Schema(description = "약정보수 사건 한 건이다.")
  public record FeeRevisionItem(
      Long revisionId,
      @Schema(allowableValues = {"INITIAL", "ADJUSTED", "CORRECTED"}) String revisionType,
      LocalDate effectiveDate,
      long totalAmount,
      @Schema(nullable = true) String reason,
      @Schema(nullable = true) Long supersedesRevisionId,
      boolean isSuperseded) {}

  @Schema(description = "수납·환급·조정 거래 한 건이다.")
  public record FeeTransactionItem(
      Long transactionId,
      @Schema(allowableValues = {"RECEIPT", "REFUND", "ADJUSTMENT"}) String transactionType,
      long amount,
      LocalDate actualDate,
      @Schema(nullable = true) String memo,
      @Schema(nullable = true) Long supersedesTransactionId,
      @Schema(nullable = true) String correctionReason,
      OffsetDateTime recordedAt,
      boolean isSuperseded) {}

  @Schema(description = "지급의무자별 합계다. 미수금과 초과수납은 상계하지 않는다.")
  public record FeePayer(
      Long payerPartyId,
      String payerName,
      @Schema(allowableValues = {"SELLER_LANDLORD", "BUYER_TENANT"}) String payerType,
      long billedTotal,
      long netReceivedTotal,
      long outstandingAmount,
      long overcollectedAmount) {}
}
