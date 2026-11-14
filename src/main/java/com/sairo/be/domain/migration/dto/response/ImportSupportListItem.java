package com.sairo.be.domain.migration.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record ImportSupportListItem(
    Long requestId,
    Long importBatchId,
    String sourceSystem,
    String status,
    @Schema(nullable = true) Long quoteAmount,
    OffsetDateTime updatedAt) {}
