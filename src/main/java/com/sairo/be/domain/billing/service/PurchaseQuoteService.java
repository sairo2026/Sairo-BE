package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.entity.PriceChangeNotice;
import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.entity.SubscriptionPlan;
import com.sairo.be.domain.billing.entity.SubscriptionStatus;
import com.sairo.be.domain.billing.repository.BillingQueryRepository;
import com.sairo.be.domain.billing.repository.BillingQueryRepository.PriceScheduleRow;
import com.sairo.be.domain.billing.service.PriceScheduleSelector.PriceSelection;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class PurchaseQuoteService {

  private final BillingContextService context;
  private final PriceScheduleSelector priceSelector;
  private final VatCalculator vatCalculator;
  private final BillingQueryRepository queryRepository;

  public PurchaseQuoteService(
      BillingContextService context,
      PriceScheduleSelector priceSelector,
      VatCalculator vatCalculator,
      BillingQueryRepository queryRepository) {
    this.context = context;
    this.priceSelector = priceSelector;
    this.vatCalculator = vatCalculator;
    this.queryRepository = queryRepository;
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

  public Quote quoteForNotice(Long officeId, Subscription subscription, PriceChangeNotice notice) {
    Instant now = context.now();
    Instant start = notice.getAppliesFromPeriodStart();
    int memberCount = context.billableMembers(officeId);
    PriceScheduleRow row =
        queryRepository
            .findPriceById(notice.getToPriceScheduleId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PRICE_SCHEDULE_NOT_PUBLISHED));
    PriceSelection selection = new PriceSelection(row, "START_CUSTOMER".equals(row.audience()));
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
        start.isAfter(now));
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
