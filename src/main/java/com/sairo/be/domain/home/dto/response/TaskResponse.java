package com.sairo.be.domain.home.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Schema(description = "업무 생성·수정 응답(TaskCreateResponse, TaskUpdateResponse)이다.")
public record TaskResponse(
    Long taskId,
    Long officeId,
    String title,
    Long assigneeMembershipId,
    LocalDate dueDate,
    @Schema(nullable = true, description = "HH:mm") String dueTime,
    @Schema(nullable = true) Long relatedCustomerId,
    @Schema(nullable = true) Long relatedPropertyId,
    @Schema(nullable = true) String memo,
    @Schema(allowableValues = {"TODO", "DONE", "CANCELLED"}) String status,
    @Schema(allowableValues = {"MANUAL", "VISIT", "CONTRACT_BALANCE", "CONTRACT_MATURITY"})
        String sourceType,
    @Schema(nullable = true) Long sourceId,
    @Schema(nullable = true) OffsetDateTime completedAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    long version) {}
