package com.sairo.be.domain.office.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "반려 재신청 시작 응답")
public record ReapplicationStartResponse(
    Long registrationId,
    boolean resolved,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {}
