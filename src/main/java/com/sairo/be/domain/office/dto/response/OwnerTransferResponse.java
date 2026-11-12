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
    OffsetDateTime rejectedAt,
    @Schema(description = "사무소 집계의 마지막 변경 시각") OffsetDateTime updatedAt,
    @Schema(description = "사무소 집계의 낙관적 잠금 버전") long version) {}
