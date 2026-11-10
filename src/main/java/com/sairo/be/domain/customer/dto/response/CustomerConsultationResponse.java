package com.sairo.be.domain.customer.dto.response;

import java.time.OffsetDateTime;

public record CustomerConsultationResponse(
    Long consultationId,
    Long customerId,
    OffsetDateTime occurredAt,
    Long authorMembershipId,
    String content,
    String consultationType,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version,
    Long officeId) {}
