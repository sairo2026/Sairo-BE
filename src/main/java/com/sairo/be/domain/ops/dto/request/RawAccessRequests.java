package com.sairo.be.domain.ops.dto.request;

import com.sairo.be.global.support.PlainText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 원문 접근 승인 요청 본문이다. */
public final class RawAccessRequests {

  private RawAccessRequests() {}

  @Schema(description = "원문 접근 승인 요청 생성")
  public record RawAccessRequestCreateRequest(
      @Schema(
              description = "접근 종류",
              allowableValues = {"RAW_VIEW", "RAW_DOWNLOAD"})
          @NotBlank @Pattern(regexp = "RAW_VIEW|RAW_DOWNLOAD") String requestType,
      @Schema(
              description = "대상 종류",
              allowableValues = {
                "OFFICE",
                "MEMBER",
                "SUPPORT_INQUIRY",
                "IMPORT_BATCH",
                "OFFICE_REGISTRATION"
              })
          @NotBlank @Pattern(regexp = "OFFICE|MEMBER|SUPPORT_INQUIRY|IMPORT_BATCH|OFFICE_REGISTRATION") String targetType,
      @Schema(description = "대상 ID") @NotNull @Min(1) Long targetId,
      @Schema(description = "목적") @NotBlank @PlainText(max = 1000) String purpose,
      @Schema(description = "접근 범위") @NotBlank @PlainText(max = 1000) String scope,
      @Schema(description = "요청 접근 시간(분). 실행 뒤 접근 창은 60분으로 고정한다") @NotNull @Min(1) @Max(60) Integer requestedExpiresMinutes,
      @Schema(
              description = "근거 종류",
              allowableValues = {"REPRESENTATIVE_REQUEST", "LEGAL_BASIS"})
          @NotBlank @Pattern(regexp = "REPRESENTATIVE_REQUEST|LEGAL_BASIS") String basisType,
      @Schema(description = "근거 내용") @NotBlank @PlainText(max = 2000) String basisNote,
      @Schema(description = "연결 문의 ID. REPRESENTATIVE_REQUEST이면 필수") @Min(1) Long linkedInquiryId,
      @Schema(description = "대상 사무소 ID") @Min(1) Long officeId,
      @Schema(description = "수혜자 운영자 ID. 생략하면 요청자 본인") @Min(1) Long beneficiaryOperatorId) {}

  @Schema(description = "원문 접근 요청 거절")
  public record RawAccessRequestRejectRequest(
      @Schema(description = "거절 사유") @NotBlank @PlainText(max = 1000) String reason) {}

  @Schema(description = "원문 접근 회수")
  public record RawAccessRevokeRequest(
      @Schema(description = "회수 사유") @NotBlank @PlainText(max = 1000) String reason) {}
}
