package com.sairo.be.domain.support.dto.response;

import java.time.OffsetDateTime;

public record EnterpriseQuoteDecisionResponse(
    Long leadId,
    String status,
    Long quoteId,
    Integer quoteVersion,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version) {}
