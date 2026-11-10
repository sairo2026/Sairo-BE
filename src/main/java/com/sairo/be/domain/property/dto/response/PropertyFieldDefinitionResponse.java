package com.sairo.be.domain.property.dto.response;

import java.time.OffsetDateTime;

public record PropertyFieldDefinitionResponse(
    Long fieldDefinitionId,
    Long categoryId,
    String fieldName,
    String fieldType,
    boolean active,
    OffsetDateTime createdAt) {}
