package com.sairo.be.domain.support.dto.response;

import java.time.OffsetDateTime;

public record EnterpriseLeadCreateResponse(
    Long leadId,
    String trackingKey,
    String status,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {}
