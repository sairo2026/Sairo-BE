package com.sairo.be.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "가입 화면이 표시한 약관 코드와 버전")
public record AgreedTermRequest(
    @Schema(description = "약관 코드", example = "TOS") @NotBlank @Size(max = 30) String termsCode,
    @Schema(description = "화면에 표시한 약관 버전", example = "2026-09-01") @NotBlank @Size(max = 20) String version) {}
