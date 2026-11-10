package com.sairo.be.domain.migration.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "이전 실행 이력과 이번 달 잔여 횟수")
public record ImportBatchListResponse(List<Item> items, int remainingThisMonth) {

  public record Item(
      Long batchId,
      String fileName,
      String targetType,
      String status,
      @Schema(nullable = true) Integer rowCount,
      OffsetDateTime createdAt,
      @Schema(nullable = true) OffsetDateTime executedAt,
      @Schema(nullable = true) OffsetDateTime cancellableUntil,
      @Schema(nullable = true) Long supportRequestId,
      @Schema(nullable = true) String supportRequestStatus,
      boolean eligibleForSupport,
      Long officeId,
      OffsetDateTime updatedAt,
      Long version) {}
}
