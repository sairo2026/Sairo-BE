package com.sairo.be.domain.migration.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "취소 결과. 외부연결·후속수정·법정보존 서류가 생긴 항목은 제외하고 사유를 담는다.")
public record ImportCancelResponse(
    Long batchId,
    Long officeId,
    String status,
    int cancelledCount,
    List<NotCancelled> notCancelled,
    @Schema(nullable = true) Integer rowCount,
    @Schema(nullable = true) OffsetDateTime executedAt,
    @Schema(nullable = true) OffsetDateTime cancellableUntil,
    @Schema(nullable = true) OffsetDateTime cancelledAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {

  public record NotCancelled(String entityType, long entityId, String reason) {}
}
