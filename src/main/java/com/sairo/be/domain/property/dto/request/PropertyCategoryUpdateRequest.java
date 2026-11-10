package com.sairo.be.domain.property.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "커스텀 카테고리 수정 요청. 보낸 필드만 바꾼다.")
public record PropertyCategoryUpdateRequest(
    @Schema(description = "카테고리명") String name, @Schema(description = "활성 여부") Boolean active) {}
