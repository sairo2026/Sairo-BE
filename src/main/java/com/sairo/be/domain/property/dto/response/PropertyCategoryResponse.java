package com.sairo.be.domain.property.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record PropertyCategoryResponse(
    Long categoryId,
    @Schema(nullable = true) Long officeId,
    String name,
    boolean isSystem,
    boolean active,
    OffsetDateTime createdAt) {}
