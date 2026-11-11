package com.sairo.be.domain.ops.dto.request;

import com.sairo.be.global.support.PlainText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 지원 문의·기업 도입 문의 운영 처리 요청 본문이다(OPS-05). */
public final class OpsSupportRequests {

  private OpsSupportRequests() {}

  @Schema(description = "문의 공개 답변")
  public record SupportInquiryOperatorReplyRequest(
      @Schema(description = "공개 답변 본문") @NotBlank @Size(max = 10_000) String content,
      @Schema(description = "고객 답변 필요 여부") @NotNull Boolean needsCustomerReply,
      @Schema(description = "답변 후 종료 여부") @NotNull Boolean closeAfterReply) {}

  @Schema(description = "문의 담당 배정")
  public record SupportInquiryAssignRequest(
      @Schema(description = "배정할 운영자 ID") @NotNull @Min(1) Long assigneeOperatorId) {}

  @Schema(description = "문의 내부 메모")
  public record SupportInquiryOperatorNoteRequest(
      @Schema(description = "내부 메모 본문") @NotBlank @Size(max = 10_000) String content) {}

  @Schema(description = "문의 우선순위 변경")
  public record SupportInquiryPriorityRequest(
      @Schema(
              description = "바꿀 우선순위",
              allowableValues = {"LOW", "NORMAL", "HIGH"})
          @NotBlank @Pattern(regexp = "LOW|NORMAL|HIGH") String priority,
      @Schema(description = "변경 사유. 내부 기록용") @PlainText(min = 0, max = 500) String reason) {}

  @Schema(description = "기업 도입 견적 발행")
  public record OperatorEnterpriseLeadQuoteRequest(
      @Schema(description = "도입 사무소 수") @NotNull @Min(1) @Max(100_000) Integer officeCount,
      @Schema(description = "인원") @NotNull @Min(1) @Max(1_000_000) Integer seatCount,
      @Schema(description = "견적 총액(원)") @NotNull @Min(0) Long quoteAmount,
      @Schema(description = "서비스 범위") @NotBlank @PlainText(max = 2000) String serviceScope,
      @Schema(description = "세금(원)") @NotNull @Min(0) Long taxAmount,
      @Schema(description = "납기") @NotNull LocalDate deliveryDueAt,
      @Schema(description = "결제 방식") @NotBlank @PlainText(max = 30) String paymentMethod,
      @Schema(description = "환불 조건") @NotBlank @PlainText(max = 2000) String refundTerms) {}

  @Schema(description = "기업 도입 문의 담당 배정")
  public record OpsEnterpriseLeadAssignRequest(
      @Schema(description = "담당으로 지정할 활성 고객지원 운영자 ID") @NotNull @Min(1) Long assignedOperatorId) {}

  @Schema(description = "기업 도입 계약 체결 기록")
  public record OpsEnterpriseLeadContractRequest(
      @Schema(description = "계약서 번호") @NotBlank @PlainText(max = 100) String contractReference,
      @Schema(description = "계약 체결 일시(현재 시각 이전)") @NotNull OffsetDateTime contractedAt) {}

  @Schema(description = "기업 도입 문의 종료")
  public record OpsEnterpriseLeadCloseRequest(
      @Schema(
              description = "종료 사유 코드",
              allowableValues = {
                "NO_RESPONSE",
                "CUSTOMER_WITHDREW",
                "OUT_OF_SCOPE",
                "DUPLICATE",
                "OTHER"
              })
          @NotBlank @Pattern(regexp = "NO_RESPONSE|CUSTOMER_WITHDREW|OUT_OF_SCOPE|DUPLICATE|OTHER") String closedReason,
      @Schema(description = "종료 사유 설명. OTHER이면 필수") @PlainText(min = 0, max = 1000)
          String closedNote) {}

  @Schema(description = "기업 도입 문의 재개")
  public record OpsEnterpriseLeadReopenRequest(
      @Schema(description = "재개 사유") @NotBlank @PlainText(max = 500) String reason,
      @Schema(description = "재개 후 담당 운영자. 생략하면 기존 담당 유지") @Min(1) Long assignedOperatorId) {}
}
