package com.sairo.be.domain.billing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "구독 구매 동의 저장 결과")
public record SubscriptionPurchaseConsentResponse(
    String termsVersion,
    long totalAmountInclVat,
    long vatAmount,
    long amountLimit,
    OffsetDateTime agreedAt) {}
