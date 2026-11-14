package com.sairo.be.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "이메일 인증 링크 발송 요청")
public record EmailVerificationRequest(
    @Schema(description = "인증 링크를 받을 주소", example = "agent@example.com")
        @NotBlank @Email @Size(max = 254) String email) {}
