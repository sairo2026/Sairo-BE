package com.sairo.be.domain.property.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record PropertyOfferResponse(
    Long offerId,
    String dealType,
    String status,
    @Schema(nullable = true) Long salePrice,
    @Schema(nullable = true) Long depositAmount,
    @Schema(nullable = true) Long monthlyRentAmount,
    @Schema(nullable = true) Long premiumAmount,
    Long officeId,
    OffsetDateTime updatedAt,
    Long version) {}
