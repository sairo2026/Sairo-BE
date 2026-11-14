package com.sairo.be.domain.ops.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(description = "카카오 인증 뒤 2차 인증 코드 확인 요청")
public record OpsMfaVerifyRequest(
    @Schema(description = "인증 앱이 보여주는 숫자 6자리 코드", example = "123456")
        @NotBlank @Pattern(regexp = "\\d{6}") String otpCode) {}
