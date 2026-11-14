package com.sairo.be.domain.migration.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record ImportSupportStatusResponse(
    Long requestId,
    Long importBatchId,
    String status,
    @Schema(nullable = true) Long quoteAmount,
    OffsetDateTime updatedAt,
    Long version) {}
