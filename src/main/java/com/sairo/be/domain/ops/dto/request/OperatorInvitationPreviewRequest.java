package com.sairo.be.domain.ops.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "운영자 초대 확인 요청")
public record OperatorInvitationPreviewRequest(
    @Schema(description = "초대 이메일 링크의 토큰") @NotBlank @Size(max = 200) String token) {}
