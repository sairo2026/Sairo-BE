package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/** 유료 이전 지원 운영 처리 응답이다(OPS-07). 고객 파일의 행 단위 원본 값은 담지 않는다. */
public final class OpsImportSupportResponses {

  private OpsImportSupportResponses() {}

  @Schema(description = "이전 지원 신청 목록 항목")
  public record ImportSupportItem(
      Long requestId,
      Long officeId,
      Long importBatchId,
      @Schema(nullable = true) String assignedOperatorName,
      String sourceSystem,
      @Schema(nullable = true) Integer estimatedRowCount,
      @Schema(nullable = true) Long quoteAmount,
      String status,
      OffsetDateTime requestedAt,
      @Schema(nullable = true) OffsetDateTime deliveredAt) {}

  @Schema(description = "견적 제시·납품 확인 결과")
  public record ImportSupportSummaryResponse(
      Long requestId,
      Long officeId,
      String sourceSystem,
      @Schema(nullable = true) Integer estimatedRowCount,
      @Schema(nullable = true) Long quoteAmount,
      String status,
      OffsetDateTime requestedAt,
      @Schema(nullable = true) OffsetDateTime deliveredAt,
      OffsetDateTime updatedAt,
      Long version) {}

  @Schema(description = "이전 지원 거절 결과")
  public record ImportSupportRejectResponse(
      Long requestId,
      String status,
      OffsetDateTime rejectedAt,
      OffsetDateTime updatedAt,
      Long version) {}

  @Schema(description = "이전 지원 처리 시작 결과")
  public record ImportSupportStartResponse(
      Long requestId,
      String status,
      Long assignedOperatorId,
      OffsetDateTime processingStartedAt,
      OffsetDateTime updatedAt,
      Long version) {}

  @Schema(description = "미리보기 요약. 행 단위 원본 값은 포함하지 않는다")
  public record PreviewSummary(
      int rowCount, int warningCount, int duplicateCount, int relationMatchCount) {}

  @Schema(description = "이전담당 매핑 저장 결과")
  public record ImportSupportMappingResponse(
      Long requestId,
      String status,
      Integer mappingVersionNo,
      @Schema(nullable = true) PreviewSummary previewSummary,
      OffsetDateTime updatedAt,
      Long version) {}

  @Schema(description = "대표 검수 제출 결과")
  public record ImportSupportSubmitReviewResponse(
      Long requestId,
      String status,
      Integer submittedMappingVersionNo,
      OffsetDateTime submittedAt,
      OffsetDateTime updatedAt,
      Long version) {}

  @Schema(description = "승인 매핑으로 이전 실행 시작 결과")
  public record ImportSupportExecuteResponse(
      Long requestId,
      Long importBatchId,
      String status,
      String batchStatus,
      Integer approvedMappingVersionNo,
      OffsetDateTime updatedAt,
      Long version) {}

  @Schema(description = "매핑 열 대응")
  public record MappingItem(
      String sourceColumn, String targetField, @Schema(nullable = true) Double confidence) {}

  @Schema(description = "가장 최근 매핑 버전")
  public record CurrentMapping(
      Integer versionNo,
      String sourceSystem,
      String targetType,
      String confirmedByType,
      OffsetDateTime createdAt,
      List<MappingItem> items) {}

  @Schema(description = "매핑 버전 이력")
  public record MappingHistoryItem(
      Integer versionNo,
      String confirmedByType,
      String confirmedByName,
      OffsetDateTime createdAt) {}

  @Schema(description = "이전 지원 상세. 현재 매핑·검수 이력·미리보기 요약이며 원문 값은 없다")
  public record ImportSupportDetailResponse(
      Long requestId,
      Long officeId,
      String officeName,
      Long importBatchId,
      String batchStatus,
      @Schema(nullable = true) Integer rowCount,
      String sourceSystem,
      @Schema(nullable = true) Integer estimatedRowCount,
      @Schema(nullable = true) Long quoteAmount,
      @Schema(nullable = true) String scopeNote,
      String status,
      @Schema(nullable = true) Long assignedOperatorId,
      @Schema(nullable = true) String assignedOperatorName,
      @Schema(nullable = true) OffsetDateTime processingStartedAt,
      @Schema(nullable = true) OffsetDateTime submittedAt,
      int changeRequestCount,
      @Schema(nullable = true) String changeRequestReason,
      @Schema(nullable = true) OffsetDateTime changeRequestedAt,
      @Schema(nullable = true) OffsetDateTime approvedAt,
      @Schema(nullable = true) Integer approvedMappingVersionNo,
      @Schema(nullable = true) OffsetDateTime executedAt,
      @Schema(nullable = true) OffsetDateTime deliveredAt,
      @Schema(nullable = true) OffsetDateTime cancelledAt,
      @Schema(nullable = true) OffsetDateTime rejectedAt,
      @Schema(nullable = true) String rejectReason,
      @Schema(nullable = true) CurrentMapping currentMapping,
      List<String> sourceColumns,
      List<MappingHistoryItem> mappingHistory,
      @Schema(nullable = true) PreviewSummary previewSummary,
      List<String> allowedActions,
      OffsetDateTime requestedAt,
      OffsetDateTime updatedAt,
      Long version) {}
}
