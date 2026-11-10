package com.sairo.be.domain.office.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "본인의 최신 신규 사무소 신청 상태")
public record OfficeRegistrationStatusResponse(
    Long registrationId,
    String status,
    int currentRevisionNo,
    String supplementReason,
    OffsetDateTime supplementDueAt,
    String rejectionReason,
    Boolean resolved,
    String revocationReason,
    OffsetDateTime revokedAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {}
