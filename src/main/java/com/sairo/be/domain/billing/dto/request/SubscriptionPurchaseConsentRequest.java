package com.sairo.be.domain.billing.dto.request;

import com.sairo.be.domain.billing.entity.SubscriptionPlan;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

@Schema(description = "구독 구매·정기결제 동의 요청. 동의만 받고 철회는 받지 않는다.")
public record SubscriptionPurchaseConsentRequest(
    @NotBlank @Size(max = 20) String termsVersion,
    @NotNull SubscriptionPlan plan,
    @NotNull @Min(1) Integer memberCount,
    @Size(max = 40) String priceVersion,
    Long priceChangeNoticeId,
    @NotNull @Min(0) Long totalAmountInclVat,
    @NotNull @Min(0) Long amountLimit,
    @NotNull OffsetDateTime periodStart,
    @NotNull OffsetDateTime periodEnd,
    @NotNull OffsetDateTime nextChargeAt,
    @AssertTrue Boolean agreed) {}
