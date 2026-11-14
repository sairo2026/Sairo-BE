package com.sairo.be.domain.support.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record SupportInquiryCreateResponse(
    Long inquiryId,
    String status,
    OffsetDateTime responseDueAt,
    @Schema(nullable = true) Long officeId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {}
