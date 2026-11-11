package com.sairo.be.domain.legal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record PrivacyRequestResponse(
    Long requestId,
    String requestType,
    String status,
    String identityStatus,
    OffsetDateTime receivedAt,
    OffsetDateTime legalDueAt,
    OffsetDateTime internalDueAt,
    @Schema(nullable = true) OffsetDateTime completedAt,
    @Schema(nullable = true) String decisionReason,
    @Schema(nullable = true) String responsibleOfficeName,
    String handlerType,
    @Schema(nullable = true, description = "보완 대기(WAITING_FOR_INFO)일 때 운영자가 요청한 보완 항목 안내")
        String supplementRequest) {}
