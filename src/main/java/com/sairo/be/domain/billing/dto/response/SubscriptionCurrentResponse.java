package com.sairo.be.domain.billing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "구독 현황 응답")
public record SubscriptionCurrentResponse(
    String status,
    String plan,
    int memberCount,
    String priceVersion,
    StartCustomer startCustomer,
    int pendingPriceChangeNoticeCount,
    OffsetDateTime trialEndsAt,
    OffsetDateTime currentPeriodEnd,
    OffsetDateTime nextChargeAt,
    Long nextChargeAmount,
    OffsetDateTime firstBillingScheduledAt,
    OffsetDateTime prePaymentSince,
    OffsetDateTime pastDueSince,
    OffsetDateTime restrictedAt,
    OffsetDateTime terminationAt,
    OffsetDateTime cancellationEffectiveAt,
    long outstandingAmount,
    boolean canRetryPayment,
    LatestInvoice latestInvoice,
    PaymentMethod paymentMethod,
    Readiness readiness,
    boolean isInReturnPeriod,
    OffsetDateTime returnPeriodEndsAt,
    Long officeId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version,
    Long subscriptionId) {

  public record StartCustomer(Long agreementId, String priceVersion, OffsetDateTime priceEndsAt) {}

  public record LatestInvoice(
      Long invoiceId, String status, OffsetDateTime nextRetryAt, String lastFailureCategory) {}

  public record PaymentMethod(String cardIssuer, String cardLast4, OffsetDateTime registeredAt) {}

  public record Readiness(boolean hasValidConsent, boolean hasBillingKey) {}
}
