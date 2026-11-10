package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.entity.SubscriptionPlan;
import com.sairo.be.domain.billing.entity.SubscriptionStatus;
import com.sairo.be.domain.billing.service.PriceScheduleSelector.PriceSelection;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class PurchaseQuoteService {

  private final BillingContextService context;
  private final PriceScheduleSelector priceSelector;
  private final VatCalculator vatCalculator;

  public PurchaseQuoteService(
      BillingContextService context,
      PriceScheduleSelector priceSelector,
      VatCalculator vatCalculator) {
    this.context = context;
    this.priceSelector = priceSelector;
    this.vatCalculator = vatCalculator;
  }

  public Quote quote(Long officeId, Subscription subscription) {
    Instant now = context.now();
    Instant start = periodStart(subscription, now);
    boolean anchored = start.isAfter(now);
    int memberCount = context.billableMembers(officeId);
    PriceSelection selection = priceSelector.select(officeId, start);
    long amount = PriceCalculator.monthlyAmount(selection.table(), memberCount);
    Instant end = BillingPeriods.boundary(start, 1);
    return new Quote(
        PriceCalculator.planFor(memberCount),
        memberCount,
        selection,
        amount,
        vatCalculator.vatAmount(amount),
        start,
        end,
        end,
        subscription.getFirstBillingScheduledAt(),
        anchored);
  }

  private Instant periodStart(Subscription subscription, Instant now) {
    Instant first = subscription.getFirstBillingScheduledAt();
    if (first != null && first.isAfter(now)) {
      return first;
    }
    SubscriptionStatus status = subscription.getStatus();
    Instant periodEnd = subscription.getCurrentPeriodEnd();
    if ((status == SubscriptionStatus.ACTIVE) && periodEnd != null && periodEnd.isAfter(now)) {
      return periodEnd;
    }
    return now;
  }

  public record Quote(
      SubscriptionPlan plan,
      int memberCount,
      PriceSelection selection,
      long totalAmountInclVat,
      long vatAmount,
      Instant periodStart,
      Instant periodEnd,
      Instant nextChargeAt,
      Instant firstBillingScheduledAt,
      boolean anchored) {

    public long amountLimit() {
      return totalAmountInclVat;
    }
  }
}
