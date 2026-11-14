package com.sairo.be.domain.migration.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "매핑 확정 결과")
public record ImportMappingConfirmResponse(
    Long batchId,
    Long officeId,
    String status,
    @Schema(nullable = true) Integer rowCount,
    @Schema(nullable = true) OffsetDateTime executedAt,
    @Schema(nullable = true) OffsetDateTime cancellableUntil,
    @Schema(nullable = true) OffsetDateTime cancelledAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {}
