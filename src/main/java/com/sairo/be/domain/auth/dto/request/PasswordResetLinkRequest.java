package com.sairo.be.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "비밀번호 재설정 링크 발송 요청")
public record PasswordResetLinkRequest(
    @Schema(description = "재설정 링크를 받을 주소", example = "agent@example.com")
        @NotBlank @Email @Size(max = 254) String email) {}
