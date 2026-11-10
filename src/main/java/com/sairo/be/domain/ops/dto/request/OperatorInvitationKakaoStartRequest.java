package com.sairo.be.domain.ops.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "운영자 초대 수락자의 카카오 인증 시작 요청")
public record OperatorInvitationKakaoStartRequest(
    @Schema(description = "초대 이메일 링크의 토큰") @NotBlank @Size(max = 200) String token) {}
