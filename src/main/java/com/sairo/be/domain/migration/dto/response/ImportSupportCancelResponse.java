package com.sairo.be.domain.migration.dto.response;

import java.time.OffsetDateTime;

public record ImportSupportCancelResponse(
    Long requestId,
    String status,
    OffsetDateTime cancelledAt,
    Long importBatchId,
    boolean directWorkAllowed,
    OffsetDateTime updatedAt,
    Long version) {}
