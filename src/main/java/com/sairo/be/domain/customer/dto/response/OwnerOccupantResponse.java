package com.sairo.be.domain.customer.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record OwnerOccupantResponse(
    Long linkId,
    Long officeId,
    Long propertyId,
    Long customerId,
    String role,
    LocalDate startDate,
    @Schema(nullable = true) LocalDate endDate,
    @Schema(nullable = true) Long confirmedByMembershipId,
    @Schema(nullable = true) OffsetDateTime confirmedAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {}
