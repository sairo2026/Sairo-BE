package com.sairo.be.domain.customer.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record PropertyInterestResponse(
    Long interestId,
    Long officeId,
    Long customerId,
    Long propertyId,
    LocalDate startDate,
    @Schema(nullable = true) LocalDate endDate,
    @Schema(nullable = true) String note,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {}
