package com.sairo.be.domain.migration.dto.response;

import java.time.OffsetDateTime;

public record ImportSupportApproveResponse(
    Long requestId,
    String status,
    int approvedMappingVersionNo,
    OffsetDateTime approvedAt,
    OffsetDateTime updatedAt,
    Long version) {}
