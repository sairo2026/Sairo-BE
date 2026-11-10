package com.sairo.be.domain.billing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "결제 요청 뒤의 구독 상태")
public record SubscriptionStateResponse(
    String status,
    String plan,
    OffsetDateTime trialEndsAt,
    OffsetDateTime currentPeriodEnd,
    OffsetDateTime pastDueSince,
    OffsetDateTime cancellationEffectiveAt,
    long outstandingAmount,
    boolean isInReturnPeriod,
    OffsetDateTime returnPeriodEndsAt,
    Long officeId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version,
    Long subscriptionId) {}
