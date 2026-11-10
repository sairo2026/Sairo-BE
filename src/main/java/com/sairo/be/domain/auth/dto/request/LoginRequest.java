package com.sairo.be.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "이메일 로그인 요청")
public record LoginRequest(
    @Schema(description = "로그인 이메일", example = "agent@example.com") @NotBlank @Size(max = 254) String email,
    @Schema(description = "로그인 비밀번호. 입력값을 변형하지 않고 해시와 대조한다") @NotNull @Size(max = 1000) String password) {}
