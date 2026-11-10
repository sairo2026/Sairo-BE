package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "인증 신청 처리 결과")
public record OpsRegistrationResponse(
    Long registrationId,
    String status,
    Long officeId,
    int currentRevisionNo,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {}
