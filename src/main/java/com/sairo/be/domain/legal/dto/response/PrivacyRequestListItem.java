package com.sairo.be.domain.legal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record PrivacyRequestListItem(
    Long requestId,
    String requestType,
    String status,
    String identityStatus,
    OffsetDateTime receivedAt,
    OffsetDateTime legalDueAt,
    @Schema(nullable = true) OffsetDateTime completedAt,
    @Schema(nullable = true) String decisionReason,
    @Schema(nullable = true) String responsibleOfficeName) {}
