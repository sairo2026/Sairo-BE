package com.sairo.be.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "표시 이름 수정 요청")
public record MeUpdateRequest(
    @Schema(description = "새 표시 이름 1~50자", example = "김태환") @NotBlank @Size(max = 200) String displayName) {}
