package com.sairo.be.domain.contract.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "계약대금 지급예정 등록 결과(ContractPaymentScheduleResponse)다. version은 부모 계약의 현재 버전이다.")
public record ContractPaymentScheduleResponse(
    Long scheduleId,
    Long contractId,
    @Schema(allowableValues = {"DEPOSIT", "INTERIM", "BALANCE"}) String installmentType,
    long scheduledAmount,
    @Schema(
            nullable = true,
            allowableValues = {"AMOUNT_MISMATCH"})
        String warning,
    Long officeId,
    long version) {

  @Schema(description = "계약대금 실지급 기록 결과(ContractPaymentTransactionResponse)다.")
  public record PaymentResult(
      Long transactionId,
      Long scheduleId,
      long actualAmount,
      Long confirmedByMembershipId,
      OffsetDateTime confirmedAt,
      Long officeId,
      long version) {}

  @Schema(
      description = "계약대금 예정과 실지급 내역, 대조 결과(ContractPaymentScheduleListResponse)다. 중개보수는 담지 않는다.")
  public record PaymentList(
      List<PaymentItem> items,
      long scheduledTotal,
      @Schema(nullable = true, description = "매매는 매매가, 전세·월세는 보증금") Long contractAmountTotal,
      @Schema(
              nullable = true,
              allowableValues = {"AMOUNT_MISMATCH"})
          String warning,
      @Schema(nullable = true) String amountMismatchReason) {}

  @Schema(description = "지급예정 한 건이다.")
  public record PaymentItem(
      Long scheduleId,
      @Schema(allowableValues = {"DEPOSIT", "INTERIM", "BALANCE"}) String installmentType,
      int sequenceNo,
      @Schema(nullable = true) LocalDate scheduledDate,
      long scheduledAmount,
      long actualPaidTotal,
      @Schema(allowableValues = {"UNPAID", "PARTIAL", "PAID"}) String paymentStatus,
      List<PaymentTransactionItem> transactions,
      Long officeId,
      long version) {}

  @Schema(description = "실지급 거래 한 건이다.")
  public record PaymentTransactionItem(
      Long transactionId,
      long actualAmount,
      LocalDate actualDate,
      Long confirmedByMembershipId,
      String confirmedByMemberName,
      OffsetDateTime confirmedAt,
      @Schema(nullable = true) Long supersedesTransactionId,
      @Schema(nullable = true) String correctionReason,
      boolean isSuperseded) {}
}
