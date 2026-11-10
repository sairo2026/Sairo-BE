package com.sairo.be.domain.migration.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record ExportResultResponse(
    Long exportId,
    String status,
    @Schema(nullable = true) String downloadUrl,
    @Schema(nullable = true) OffsetDateTime expiresAt,
    OffsetDateTime fileExpiresAt,
    @Schema(nullable = true) String failureReason) {}
