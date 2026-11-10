package com.sairo.be.domain.migration.dto.response;

import java.time.OffsetDateTime;

public record ExportListItem(
    Long exportId,
    String scope,
    String status,
    OffsetDateTime createdAt,
    OffsetDateTime fileExpiresAt) {}
