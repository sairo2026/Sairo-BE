package com.sairo.be.domain.migration.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "실행·재처리 시작 응답. 비동기로 처리되어 COMPLETED 또는 FAILED로 바뀐다.")
public record ImportExecuteResponse(
    Long batchId,
    String status,
    @Schema(nullable = true) OffsetDateTime cancellableUntil,
    Long officeId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {}
