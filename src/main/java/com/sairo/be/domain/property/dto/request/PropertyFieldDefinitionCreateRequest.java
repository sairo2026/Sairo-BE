package com.sairo.be.domain.property.dto.request;

import com.sairo.be.domain.property.entity.FieldType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "커스텀 필드 정의 추가 요청")
public record PropertyFieldDefinitionCreateRequest(
    @Schema(description = "필드명", example = "해당층") @NotNull String fieldName,
    @Schema(description = "필드 타입") @NotNull FieldType fieldType) {}
