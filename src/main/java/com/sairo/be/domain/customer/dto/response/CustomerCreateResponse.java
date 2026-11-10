package com.sairo.be.domain.customer.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record CustomerCreateResponse(
    Long customerId,
    @Schema(nullable = true) OffsetDateTime deletedAt,
    Long officeId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version,
    String processingBasis) {}
