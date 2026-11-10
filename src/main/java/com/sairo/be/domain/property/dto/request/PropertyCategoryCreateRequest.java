package com.sairo.be.domain.property.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "커스텀 카테고리 추가 요청")
public record PropertyCategoryCreateRequest(
    @Schema(description = "카테고리명", example = "타운하우스") @NotNull String name) {}
