package com.sairo.be.domain.property.dto.response;

import java.time.OffsetDateTime;

public record PropertyCreateResponse(
    Long propertyId,
    String registrationStage,
    Long officeId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {}
