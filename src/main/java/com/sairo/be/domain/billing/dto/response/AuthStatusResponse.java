package com.sairo.be.domain.billing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "사업자 인증 중단 상태")
public record AuthStatusResponse(
    boolean authSuspended,
    OffsetDateTime suspendedAt,
    OffsetDateTime reauthDeadlineAt,
    Long trialRemainingSeconds) {}
