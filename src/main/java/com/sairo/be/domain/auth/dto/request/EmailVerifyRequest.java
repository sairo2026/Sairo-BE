package com.sairo.be.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "이메일 인증 토큰 확인 요청")
public record EmailVerifyRequest(
    @Schema(description = "발송된 인증 토큰") @NotBlank @Size(max = 200) String token) {}
