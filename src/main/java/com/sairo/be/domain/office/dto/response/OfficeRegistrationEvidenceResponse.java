package com.sairo.be.domain.office.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "증빙 제출 응답")
public record OfficeRegistrationEvidenceResponse(
    Long registrationId,
    String status,
    int currentRevisionNo,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {}
