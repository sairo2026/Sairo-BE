package com.sairo.be.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "비밀번호 재설정 완료 요청")
public record PasswordResetRequest(
    @Schema(description = "발송된 재설정 토큰") @NotBlank @Size(max = 200) String token,
    @Schema(description = "새 비밀번호. 코드 포인트 기준 8~12자이며 입력값을 보존한다") @NotNull String newPassword) {}
