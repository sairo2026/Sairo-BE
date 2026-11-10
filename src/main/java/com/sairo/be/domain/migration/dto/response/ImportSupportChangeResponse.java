package com.sairo.be.domain.migration.dto.response;

import java.time.OffsetDateTime;

public record ImportSupportChangeResponse(
    Long requestId,
    String status,
    int changeRequestCount,
    OffsetDateTime changeRequestedAt,
    OffsetDateTime updatedAt,
    Long version) {}
