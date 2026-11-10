package com.sairo.be.domain.migration.dto.response;

import java.time.OffsetDateTime;

public record ImportSupportPaymentConfirmResponse(
    Long requestId, String status, OffsetDateTime paidAt, OffsetDateTime updatedAt, Long version) {}
