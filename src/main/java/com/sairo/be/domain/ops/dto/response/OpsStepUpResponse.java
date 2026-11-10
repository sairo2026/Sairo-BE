package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "운영자 재인증 응답")
public record OpsStepUpResponse(OffsetDateTime stepUpValidUntil) {}
