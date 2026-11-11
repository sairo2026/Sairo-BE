package com.sairo.be.domain.migration.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(
    description =
        "유료 이전 지원 상세. scopeNote, paymentOrderId, paymentAmount, paidAt은 견적 범위와 주문 정보를"
            + " 다시 읽기 위해 추가한 선택 필드다.")
public record ImportSupportDetailResponse(
    Long requestId,
    Long importBatchId,
    String sourceSystem,
    Integer estimatedRowCount,
    @Schema(nullable = true) Long quoteAmount,
    @Schema(nullable = true) String scopeNote,
    @Schema(nullable = true) String paymentOrderId,
    @Schema(nullable = true) Long paymentAmount,
    @Schema(nullable = true) OffsetDateTime paidAt,
    String status,
    @Schema(nullable = true) String assignedOperatorName,
    @Schema(nullable = true) OffsetDateTime processingStartedAt,
    @Schema(nullable = true) OffsetDateTime submittedAt,
    int changeRequestCount,
    @Schema(nullable = true) String changeRequestReason,
    @Schema(nullable = true) OffsetDateTime changeRequestedAt,
    @Schema(nullable = true) OffsetDateTime approvedAt,
    @Schema(nullable = true) Integer approvedMappingVersionNo,
    @Schema(nullable = true) OffsetDateTime deliveredAt,
    @Schema(nullable = true) OffsetDateTime cancelledAt,
    @Schema(nullable = true) String cancelReason,
    @Schema(nullable = true) OffsetDateTime rejectedAt,
    @Schema(nullable = true) String rejectReason,
    boolean directWorkAllowed,
    List<String> allowedActions,
    String batchStatus,
    @Schema(nullable = true) ExecutionResult executionResult,
    @Schema(nullable = true) SubmittedMapping submittedMapping,
    @Schema(nullable = true) PreviewSummary previewSummary,
    OffsetDateTime requestedAt,
    OffsetDateTime updatedAt,
    Long version) {

  public record ExecutionResult(long succeeded, long failed, long unprocessed) {}

  public record SubmittedMapping(
      int versionNo,
      String sourceSystem,
      String targetType,
      String confirmedByType,
      OffsetDateTime createdAt,
      List<Item> items) {

    public record Item(
        String sourceColumn, String targetField, @Schema(nullable = true) Double confidence) {}
  }

  public record PreviewSummary(
      int rowCount, int warningCount, int duplicateCount, int relationMatchCount) {}
}
