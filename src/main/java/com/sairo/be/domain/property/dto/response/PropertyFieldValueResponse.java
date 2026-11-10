package com.sairo.be.domain.property.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record PropertyFieldValueResponse(
    Long fieldDefinitionId,
    @Schema(nullable = true) Object value,
    Long officeId,
    OffsetDateTime updatedAt,
    Long version) {}
