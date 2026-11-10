package com.sairo.be.domain.migration.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record ImportSupportQuoteAcceptResponse(
    Long requestId,
    Long officeId,
    String sourceSystem,
    @Schema(nullable = true) Integer estimatedRowCount,
    @Schema(nullable = true) Long quoteAmount,
    String paymentOrderId,
    Long paymentAmount,
    String status,
    OffsetDateTime requestedAt,
    @Schema(nullable = true) OffsetDateTime deliveredAt) {}
