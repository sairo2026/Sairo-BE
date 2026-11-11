package com.sairo.be.domain.home.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Schema(description = "업무 목록(TaskListResponse)의 항목이다.")
public record TaskListItem(
    Long taskId,
    String title,
    Long assigneeMembershipId,
    String assigneeName,
    LocalDate dueDate,
    @Schema(nullable = true, description = "HH:mm") String dueTime,
    @Schema(nullable = true) Long relatedCustomerId,
    @Schema(nullable = true) String relatedCustomerName,
    @Schema(nullable = true) Long relatedPropertyId,
    @Schema(nullable = true) String relatedPropertyName,
    @Schema(nullable = true) String memo,
    @Schema(nullable = true) OffsetDateTime completedAt,
    @Schema(allowableValues = {"TODO", "DONE", "CANCELLED"}) String status,
    @Schema(allowableValues = {"MANUAL", "VISIT", "CONTRACT_BALANCE", "CONTRACT_MATURITY"})
        String sourceType,
    Long officeId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    long version) {}
