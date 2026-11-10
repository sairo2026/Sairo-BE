package com.sairo.be.domain.ops.dto.request;

import com.sairo.be.global.support.PlainText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "인증 완료 사무소 회수 요청")
public record OpsOfficeRevokeRequest(
    @Schema(description = "회수 사유") @NotBlank @PlainText(max = 1000) String reason) {}
