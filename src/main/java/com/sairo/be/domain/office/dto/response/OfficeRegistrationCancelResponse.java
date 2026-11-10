package com.sairo.be.domain.office.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "사무소 등록 신청 취소 응답")
public record OfficeRegistrationCancelResponse(
    Long registrationId,
    String status,
    OffsetDateTime cancelledAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {}
