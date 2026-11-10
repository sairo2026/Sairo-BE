package com.sairo.be.domain.migration.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "이전 실행 단위 상세. 시트 목록은 업로드 뒤 다시 읽기 위해 추가한 선택 필드다.")
public record ImportBatchDetailResponse(
    Long batchId,
    String fileName,
    String targetType,
    String status,
    @Schema(nullable = true) Integer rowCount,
    @Schema(nullable = true) Counts counts,
    @Schema(nullable = true) OffsetDateTime executedAt,
    @Schema(nullable = true) OffsetDateTime cancellableUntil,
    @Schema(nullable = true) OffsetDateTime cancelledAt,
    @Schema(nullable = true) String failureReason,
    @Schema(nullable = true) CancelSummary cancelSummary,
    List<ImportSheetResponse> sheets,
    String selectedSheetName,
    Long officeId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {

  public record Counts(long total, long succeeded, long failed, long unprocessed) {}

  public record CancelSummary(
      int cancelledCount, List<ImportCancelResponse.NotCancelled> notCancelled) {}
}
