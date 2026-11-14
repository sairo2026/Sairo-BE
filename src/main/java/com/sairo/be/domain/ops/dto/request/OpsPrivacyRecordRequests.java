package com.sairo.be.domain.ops.dto.request;

import com.sairo.be.global.support.PlainText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 비회원 권리 요청·수사기관 등 자료 요청·공급자 계약서 사본 요청 본문이다(OPS-15·16·17). */
public final class OpsPrivacyRecordRequests {

  private OpsPrivacyRecordRequests() {}

  @Schema(description = "비회원 권리 요청 본인 확인 결과와 전환·종결")
  public record GuestPrivacyIntakeResolveRequest(
      @Schema(
              description = "본인 확인 결과",
              allowableValues = {"VERIFIED", "FAILED"})
          @NotBlank @Pattern(regexp = "VERIFIED|FAILED") String verificationResult,
      @Schema(
              description = "전환 또는 종결. FAILED이면 CLOSE만 허용",
              allowableValues = {"CONVERT", "CLOSE"})
          @NotBlank @Pattern(regexp = "CONVERT|CLOSE") String action,
      @Schema(description = "적용 법적 근거. CONVERT이면 필수") @PlainText(min = 0, max = 2000)
          String legalBasis,
      @Schema(description = "요청한 개인정보와 처리 범위. CONVERT이면 필수") @PlainText(min = 0, max = 2000)
          String requestScope,
      @Schema(description = "법정 처리기한. 기본 접수일 10일 뒤") OffsetDateTime legalDueAt,
      @Schema(description = "내부 목표 처리기한. 기본 접수일 7일 뒤") OffsetDateTime internalDueAt,
      @Schema(
              description = "종결 사유. CLOSE이면 필수",
              allowableValues = {
                "DUPLICATE",
                "NOT_SUBJECT",
                "UNVERIFIABLE",
                "WITHDRAWN_BY_REQUESTER"
              })
          @Pattern(regexp = "DUPLICATE|NOT_SUBJECT|UNVERIFIABLE|WITHDRAWN_BY_REQUESTER") String closeReason) {}

  @Schema(description = "수사기관 등 자료 요청 접수 기록")
  public record AuthorityRequestCreateRequest(
      @Schema(description = "요청을 받은 시각") @NotNull OffsetDateTime receivedAt,
      @Schema(
              description = "기관 종류",
              allowableValues = {"INVESTIGATION", "COURT", "SUPERVISORY", "OTHER"})
          @NotBlank @Pattern(regexp = "INVESTIGATION|COURT|SUPERVISORY|OTHER") String agencyType,
      @Schema(description = "기관 이름") @NotBlank @PlainText(max = 200) String agencyName,
      @Schema(description = "문서 번호") @PlainText(min = 0, max = 100) String documentReference,
      @Schema(description = "확인한 법적 근거 메모") @NotBlank @PlainText(max = 2000) String legalBasisNote,
      @Schema(description = "대상 사무소") @Min(1) Long targetOfficeId,
      @Schema(description = "대상 회원") @Min(1) Long targetUserId,
      @Schema(description = "요청받은 자료의 범위") @NotBlank @PlainText(max = 2000)
          String requestedItems) {}

  @Schema(description = "수사기관 등 자료 요청 제공 결정과 종결")
  public record AuthorityRequestCloseRequest(
      @Schema(
              description = "제공 결정. 제공·일부 제공은 기록자와 다른 보안관리자만",
              allowableValues = {
                "PROVIDED",
                "PARTIALLY_PROVIDED",
                "REFUSED",
                "FORWARDED_TO_OFFICE"
              })
          @NotBlank @Pattern(regexp = "PROVIDED|PARTIALLY_PROVIDED|REFUSED|FORWARDED_TO_OFFICE") String decision,
      @Schema(description = "제공한 자료의 범위. 제공 결정이면 필수") @PlainText(min = 0, max = 2000)
          String providedItems,
      @Schema(description = "제공 시각. 제공 결정이면 필수") OffsetDateTime providedAt,
      @Schema(
              description = "사무소 통지 검토 결과",
              allowableValues = {"NOTIFIED", "DEFERRED", "NOT_ALLOWED"})
          @NotBlank @Pattern(regexp = "NOTIFIED|DEFERRED|NOT_ALLOWED") String officeNoticeStatus,
      @Schema(description = "종결 메모") @PlainText(min = 0, max = 1000) String closeNote) {}

  @Schema(description = "공급자 계약서 사본 등록")
  public record ProviderContractCopyCreateRequest(
      @Schema(
              description = "문서 종류",
              allowableValues = {
                "SERVICE_CONTRACT",
                "PROCESSING_TERMS",
                "SUBPROCESSOR_NOTICE",
                "OTHER"
              })
          @NotBlank @Pattern(regexp = "SERVICE_CONTRACT|PROCESSING_TERMS|SUBPROCESSOR_NOTICE|OTHER") String documentKind,
      @Schema(description = "버전 이름") @NotBlank @PlainText(max = 40) String versionLabel,
      @Schema(description = "체결일") LocalDate signedOn,
      @Schema(description = "유효 종료일. 체결일 이후") LocalDate validUntil,
      @Schema(description = "비공개 저장소의 객체 키") @NotBlank @PlainText(max = 300) String storageKey,
      @Schema(description = "사본 SHA-256 해시(64자리 16진수)")
          @NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String contentSha256,
      @Schema(description = "재수탁자 변경 사전 고지 시각") OffsetDateTime subprocessorChangeNotifiedAt) {}
}
