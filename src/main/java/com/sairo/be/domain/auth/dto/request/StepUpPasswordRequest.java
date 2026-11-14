package com.sairo.be.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "비밀번호 재인증 요청")
public record StepUpPasswordRequest(
    @Schema(description = "현재 계정 비밀번호. 입력값을 변형하지 않고 해시와 대조한다") @NotNull @Size(max = 1000) String password) {}
