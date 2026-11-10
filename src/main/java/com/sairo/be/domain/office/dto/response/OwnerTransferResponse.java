package com.sairo.be.domain.office.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "대표 이전 상태")
public record OwnerTransferResponse(
    Long transferId,
    Long officeId,
    String fromDisplayName,
    String toDisplayName,
    String status,
    OffsetDateTime requestedAt,
    OffsetDateTime acceptDeadlineAt,
    OffsetDateTime acceptedAt,
    OffsetDateTime completedAt,
    OffsetDateTime withdrawnAt,
    String rejectionReason,
    OffsetDateTime rejectedAt) {}
