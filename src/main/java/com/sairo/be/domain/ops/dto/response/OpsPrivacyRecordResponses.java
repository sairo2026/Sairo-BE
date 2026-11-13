package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 비회원 권리 요청·수사기관 등 자료 요청·공급자 계약서 사본 응답이다(OPS-15·16·17). */
public final class OpsPrivacyRecordResponses {

  private OpsPrivacyRecordResponses() {}

  @Schema(description = "비회원 권리 요청 접수 큐 항목. 연락처는 가려서 보인다")
  public record GuestPrivacyIntakeItem(
      Long intakeId,
      Long officeId,
      String officeName,
      String linkKind,
      String requestType,
      String requesterRole,
      String contactChannel,
      String maskedContact,
      String verificationStatus,
      String status,
      OffsetDateTime receivedAt,
      OffsetDateTime internalDueAt,
      Long privacyRequestId) {}

  @Schema(description = "비회원 권리 요청 접수 단건. 연락처는 가려서 보이고 요청 본문 원문은 담지 않는다")
  public record GuestPrivacyIntakeDetailResponse(
      Long intakeId,
      Long officeId,
      String officeName,
      String linkKind,
      String requestType,
      String requesterRole,
      String contactChannel,
      String maskedContact,
      String verificationStatus,
      String status,
      OffsetDateTime receivedAt,
      OffsetDateTime internalDueAt,
      Long privacyRequestId,
      String closedReason) {}

  @Schema(description = "비회원 권리 요청 처리 결과")
  public record GuestPrivacyIntakeResolveResponse(
      Long intakeId,
      String status,
      String verificationStatus,
      Long privacyRequestId,
      String closedReason) {}

  @Schema(description = "수사기관 등 자료 요청 목록 항목")
  public record AuthorityRequestItem(
      Long requestId,
      OffsetDateTime receivedAt,
      String agencyType,
      String agencyName,
      String documentReference,
      String decision,
      String officeNoticeStatus,
      @Schema(description = "접수를 기록한 운영자") Long recordedByOperatorId,
      @Schema(description = "접수를 기록한 운영자 이름") String recordedByOperatorName,
      OffsetDateTime closedAt,
      @Schema(description = "기록 삭제 예정일. 종결 때 정한다") LocalDate retentionUntil) {}

  @Schema(description = "수사기관 등 자료 요청 기록")
  public record AuthorityRequestResponse(
      Long requestId,
      OffsetDateTime receivedAt,
      String agencyType,
      String agencyName,
      String documentReference,
      String legalBasisNote,
      Long targetOfficeId,
      Long targetUserId,
      String requestedItems,
      String decision,
      String providedItems,
      OffsetDateTime providedAt,
      String officeNoticeStatus,
      Long recordedByOperatorId,
      Long approvedByOperatorId,
      OffsetDateTime closedAt,
      LocalDate retentionUntil) {}

  @Schema(description = "공급자 계약서 사본 목록 항목. 저장 위치는 담지 않는다")
  public record ProviderContractCopyItem(
      Long copyId,
      String documentKind,
      String versionLabel,
      LocalDate signedOn,
      LocalDate validUntil,
      String contentSha256,
      OffsetDateTime subprocessorChangeNotifiedAt,
      OffsetDateTime uploadedAt) {}

  @Schema(description = "공급자 계약서 사본 목록")
  public record ProviderContractCopyListResponse(List<ProviderContractCopyItem> items) {}

  @Schema(description = "공급자 계약서 사본 등록 결과")
  public record ProviderContractCopyResponse(
      Long copyId,
      Long integrationId,
      String documentKind,
      String versionLabel,
      LocalDate signedOn,
      LocalDate validUntil,
      String contentSha256,
      OffsetDateTime subprocessorChangeNotifiedAt,
      Long uploadedByOperatorId,
      OffsetDateTime uploadedAt,
      LocalDate retentionUntil) {}
}
