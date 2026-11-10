package com.sairo.be.domain.ops.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(description = "운영자 초대 수락 요청")
public record OperatorInvitationAcceptRequest(
    @Schema(description = "인증 앱이 보여주는 숫자 6자리 코드", example = "123456")
        @NotBlank @Pattern(regexp = "\\d{6}") String otpCode) {}
