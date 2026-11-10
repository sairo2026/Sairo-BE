package com.sairo.be.domain.property.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "커스텀 필드 정의 수정 요청. 이름 수정과 비활성화만 허용한다.")
public record PropertyFieldDefinitionUpdateRequest(
    @Schema(description = "필드명") String fieldName, @Schema(description = "활성 여부") Boolean active) {}
