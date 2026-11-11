package com.sairo.be.domain.support.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record SupportInquiryListItem(
    Long inquiryId,
    @Schema(nullable = true) Long officeId,
    String category,
    String status,
    String priority,
    @Schema(nullable = true) String assignedOperatorName,
    OffsetDateTime responseDueAt,
    @Schema(nullable = true) OffsetDateTime answeredAt,
    @Schema(nullable = true) OffsetDateTime closedAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {}
