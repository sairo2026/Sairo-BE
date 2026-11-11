package com.sairo.be.domain.ops.dto.request;

import com.sairo.be.global.support.PlainText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

/** 운영 콘솔 환불 검토·대사 해결·증빙 기록 요청 본문이다(OPS-06·14). */
public final class OpsBillingRequests {

  private OpsBillingRequests() {}

  @Schema(description = "환불 검토. 승인하면 실행을 기다리고 거절하면 사유를 남긴다")
  public record RefundReviewRequest(
      @Schema(
              description = "검토 결과",
              allowableValues = {"APPROVE", "REJECT"})
          @NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision,
      @Schema(description = "검토 메모. 거절이면 필수이며 대표에게 거절 사유로 보인다") @PlainText(min = 0, max = 1000)
          String note,
      @Schema(description = "환불 사유 사건 ID. 신청에 사건 ID가 없을 때 승인에서 필수")
          @Pattern(regexp = "^[1-9][0-9]{0,17}$") String reasonEventId) {}

  @Schema(description = "대사 불일치 원인과 해결 근거")
  public record ReconciliationResolveRequest(
      @Schema(description = "불일치 원인") @NotBlank @PlainText(max = 2000) String differenceReason,
      @Schema(description = "해결 근거") @NotBlank @PlainText(max = 2000) String resolutionEvidence,
      @Schema(description = "대사 차이 담당 운영자. 생략하면 요청한 운영자") Long assigneeOperatorId) {}

  @Schema(description = "국세청 서비스에서 발급한 증빙 결과 기록")
  public record TaxDocumentRecordRequest(
      @Schema(
              description = "증빙 종류",
              allowableValues = {"TAX_INVOICE", "CASH_RECEIPT", "EXEMPT_INVOICE"})
          @NotBlank @Pattern(regexp = "TAX_INVOICE|CASH_RECEIPT|EXEMPT_INVOICE") String documentKind,
      @Schema(description = "전자 발급 여부") @NotNull Boolean isElectronic,
      @Schema(
              description = "기록할 상태",
              allowableValues = {"ISSUED", "ISSUE_FAILED", "NOT_REQUIRED", "PENDING"})
          @NotBlank @Pattern(regexp = "ISSUED|ISSUE_FAILED|NOT_REQUIRED|PENDING") String status,
      @Schema(description = "승인번호. ISSUED이면 필수") @PlainText(min = 0, max = 40)
          String approvalNumber,
      @Schema(description = "발급일. ISSUED이면 필수") LocalDate issuedOn,
      @Schema(description = "공급가액. ISSUED이면 필수이며 청구서 공급가액과 같아야 한다") Long supplyAmount,
      @Schema(description = "세액. ISSUED이면 필수이며 공급가액과 더해 청구서 총액과 같아야 한다") Long vatAmount,
      @Schema(description = "사유 메모. ISSUE_FAILED이면 필수") @PlainText(min = 0, max = 2000)
          String operatorNote) {}

  @Schema(description = "수정 발급 기록. 원본은 고치지 않고 새 기록을 만든다")
  public record TaxDocumentAmendRequest(
      @Schema(description = "수정 발급 승인번호") @NotBlank @PlainText(max = 40) String approvalNumber,
      @Schema(description = "수정 발급일") @NotNull LocalDate issuedOn,
      @Schema(description = "수정 공급가액. 음수 가능") @NotNull Long supplyAmount,
      @Schema(description = "수정 세액. 음수 가능") @NotNull Long vatAmount,
      @Schema(description = "수정 사유") @NotBlank @PlainText(max = 2000) String operatorNote) {}
}
