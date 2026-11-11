package com.sairo.be.domain.legal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record PrivacyRequestCreateResponse(
    Long requestId,
    @Schema(nullable = true) Long supportInquiryId,
    String requestType,
    String identityStatus,
    String status,
    OffsetDateTime receivedAt,
    OffsetDateTime legalDueAt,
    OffsetDateTime internalDueAt) {}
