package com.sairo.be.domain.ops.dto.request;

import com.sairo.be.global.support.PlainText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

@Schema(description = "인증 신청 보완 요청")
public record OpsRegistrationSupplementRequest(
    @Schema(description = "고객에게 공개되는 보완 사유") @NotBlank @PlainText(max = 1000) String reason,
    @Schema(description = "보완 기한, 요청 시각 이후") @NotNull OffsetDateTime supplementDueAt) {}
