package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/** 개인정보 권리 요청 처리, 반환·보관권한, 파기 큐 응답이다(OPS-07·13). 요청자 이름·연락처는 담지 않는다. */
public final class OpsPrivacyOpsResponses {

  private OpsPrivacyOpsResponses() {}

  @Schema(description = "개인정보 권리 요청 큐 항목")
  public record PrivacyRequestItem(
      Long requestId,
      @Schema(nullable = true) Long officeId,
      String requestType,
      String identityStatus,
      String status,
      OffsetDateTime receivedAt,
      String legalBasis,
      String requestScope,
      OffsetDateTime legalDueAt,
      OffsetDateTime internalDueAt,
      @Schema(nullable = true, description = "ON_TRACK, APPROACHING, OVERDUE. 완료·거절이면 null")
          String dueState,
      @Schema(nullable = true) String decisionReason,
      @Schema(nullable = true) Long assignedOperatorId,
      @Schema(nullable = true) String assignedOperatorName,
      @Schema(nullable = true) OffsetDateTime completedAt,
      String handlerType) {}

  @Schema(description = "개인정보 권리 요청 처리 이력")
  public record PrivacyRequestEventItem(
      Long eventId,
      String eventType,
      @Schema(nullable = true) String previousStatus,
      String newStatus,
      String actionDetail,
      @Schema(nullable = true) String actorOperatorName,
      OffsetDateTime occurredAt) {}

  @Schema(description = "개인정보 권리 요청 상세")
  public record PrivacyRequestDetailResponse(
      Long requestId,
      @Schema(nullable = true) Long officeId,
      String requestType,
      String identityStatus,
      String status,
      OffsetDateTime receivedAt,
      String legalBasis,
      String requestScope,
      OffsetDateTime legalDueAt,
      OffsetDateTime internalDueAt,
      @Schema(nullable = true) String dueState,
      @Schema(nullable = true) String decisionReason,
      @Schema(nullable = true) String completionEvidence,
      @Schema(nullable = true) Long assignedOperatorId,
      @Schema(nullable = true) String assignedOperatorName,
      @Schema(nullable = true) OffsetDateTime completedAt,
      List<PrivacyRequestEventItem> events,
      @Schema(description = "권리 요청의 변경 번호. 처리 요청의 If-Match에 보내고 처리마다 1씩 오른다") Long version,
      String handlerType,
      @Schema(description = "지금 상태에서 받을 수 있는 처리 단계") List<String> allowedActions) {}

  @Schema(description = "개인정보 권리 요청 처리 결과")
  public record PrivacyRequestProcessResponse(
      Long requestId, Long eventId, String status, OffsetDateTime occurredAt) {}

  @Schema(description = "반환권한 현황 항목")
  public record ReturnGrantItem(
      Long grantId,
      Long officeId,
      Long grantedToUserId,
      OffsetDateTime grantedAt,
      OffsetDateTime expiresAt,
      @Schema(nullable = true) OffsetDateTime revokedAt,
      @Schema(nullable = true) Long replacedByGrantId) {}

  @Schema(description = "보관권한 현황 항목")
  public record ArchiveGrantItem(
      Long grantId,
      Long officeId,
      Long grantedToUserId,
      @Schema(nullable = true) String contractReference,
      OffsetDateTime grantedAt,
      @Schema(nullable = true) OffsetDateTime expiresAt,
      @Schema(nullable = true) OffsetDateTime terminatedAt,
      @Schema(nullable = true) Long replacedByGrantId) {}

  @Schema(description = "반환권한자 변경 결과")
  public record ReturnGrantTransferResponse(
      Long previousGrantId,
      OffsetDateTime revokedAt,
      Long newGrantId,
      Long newGranteeUserId,
      OffsetDateTime grantedAt,
      OffsetDateTime expiresAt) {}

  @Schema(description = "보관권한자 변경 결과")
  public record ArchiveGrantTransferResponse(
      Long previousGrantId,
      OffsetDateTime terminatedAt,
      Long newGrantId,
      Long newGranteeUserId,
      OffsetDateTime grantedAt,
      @Schema(nullable = true) OffsetDateTime expiresAt) {}

  @Schema(description = "파기 큐 항목")
  public record DeletionQueueItem(
      Long queueId,
      String targetType,
      Long targetId,
      @Schema(nullable = true) Long officeId,
      String reason,
      OffsetDateTime scheduledAt,
      @Schema(nullable = true) OffsetDateTime executedAt,
      @Schema(nullable = true) Long executedByOperatorId,
      Long targetCount,
      Long excludedCount,
      @Schema(nullable = true) String result,
      @Schema(nullable = true) String storageResult,
      @Schema(nullable = true) String searchIndexResult,
      @Schema(nullable = true) String fileResult,
      @Schema(nullable = true) String temporaryExportResult,
      @Schema(nullable = true) OffsetDateTime backupExpiresAt,
      Integer retryCount,
      @Schema(nullable = true) String exclusionReason,
      @Schema(nullable = true) Long holdApprovedByOperatorId,
      @Schema(nullable = true) OffsetDateTime holdExpiresAt,
      @Schema(nullable = true) OffsetDateTime exclusionReviewedAt,
      @Schema(nullable = true) Long verifiedByOperatorId,
      @Schema(nullable = true) OffsetDateTime verifiedAt,
      @Schema(description = "검증 요청으로 받은 파기 검증 증빙. 검증 전에는 비어 있다", nullable = true)
          String verificationEvidence,
      @Schema(nullable = true) OffsetDateTime cancelledAt,
      @Schema(nullable = true) Long cancelledByOperatorId,
      @Schema(nullable = true) String cancelReason,
      OffsetDateTime createdAt) {}

  @Schema(description = "파기 저장소 대상")
  public record TargetRef(String targetType, Long targetId) {}

  @Schema(description = "파기 파일 대상")
  public record ObjectRef(String objectKey) {}

  @Schema(description = "파기 실행 전 미리보기")
  public record DeletionQueuePreviewResponse(
      Long targetCount,
      Long excludedCount,
      List<TargetRef> storageTargets,
      List<TargetRef> searchIndexTargets,
      List<ObjectRef> fileTargets,
      List<ObjectRef> temporaryExportTargets,
      List<String> holdReasons) {}

  @Schema(description = "보존 예외 처리 결과")
  public record DeletionQueueExcludeResponse(
      Long queueId,
      String result,
      String exclusionReason,
      OffsetDateTime holdExpiresAt,
      OffsetDateTime exclusionReviewedAt) {}

  @Schema(description = "파기 실행 결과. 검증 전 result는 null이다")
  public record DeletionQueueExecuteResponse(
      Long queueId,
      @Schema(nullable = true) String result,
      OffsetDateTime executedAt,
      String storageResult,
      String searchIndexResult,
      String fileResult,
      String temporaryExportResult,
      Integer retryCount,
      @Schema(nullable = true) OffsetDateTime verifiedAt) {}

  @Schema(description = "파기 검증 결과")
  public record DeletionQueueVerifyResponse(
      Long queueId,
      @Schema(nullable = true) String result,
      String storageResult,
      String searchIndexResult,
      String fileResult,
      String temporaryExportResult,
      @Schema(nullable = true) OffsetDateTime backupExpiresAt,
      @Schema(nullable = true) Long verifiedByOperatorId,
      @Schema(nullable = true) OffsetDateTime verifiedAt) {}

  @Schema(description = "파기 취소 결과")
  public record DeletionQueueCancelResponse(
      Long queueId, OffsetDateTime cancelledAt, Long cancelledByOperatorId) {}
}
