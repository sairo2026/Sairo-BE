package com.sairo.be.domain.ops.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(description = "고위험 작업용 5분 재인증 요청")
public record OpsStepUpRequest(
    @Schema(description = "인증 앱이 보여주는 숫자 6자리 코드", example = "123456")
        @NotBlank @Pattern(regexp = "\\d{6}") String otpCode) {}
