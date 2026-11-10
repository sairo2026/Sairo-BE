package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "인증심사 대기 목록 항목")
public record OperatorRegistrationListItemResponse(
    Long registrationId,
    String requestedOfficeName,
    String businessRegistrationNumber,
    String status,
    OffsetDateTime createdAt) {}
