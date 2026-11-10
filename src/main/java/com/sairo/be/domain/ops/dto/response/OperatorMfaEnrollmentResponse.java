package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "2차 인증 등록 정보")
public record OperatorMfaEnrollmentResponse(
    String otpauthUri, String secret, OffsetDateTime enrollmentExpiresAt) {}
