package com.sairo.be.domain.support.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record EnterpriseLeadStatusResponse(
    Long leadId,
    String status,
    @Schema(nullable = true) Long quoteId,
    @Schema(nullable = true) Integer quoteVersion,
    @Schema(nullable = true) Long quoteAmount,
    @Schema(nullable = true) OffsetDateTime quoteExpiresAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {}
