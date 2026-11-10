package com.sairo.be.domain.billing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "해지 예약 결과")
public record SubscriptionCancelResponse(OffsetDateTime effectiveAt, OffsetDateTime revokedAt) {}
