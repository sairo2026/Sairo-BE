package com.sairo.be.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "재인증 완료 응답")
public record StepUpResponse(
    @Schema(description = "재인증 완료 상태") boolean stepUpValid,
    @Schema(description = "재인증 만료 시각") OffsetDateTime stepUpExpiresAt) {}
