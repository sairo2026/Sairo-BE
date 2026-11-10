package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.repository.BillingQueryRepository;
import com.sairo.be.domain.billing.repository.SubscriptionRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class BillingContextService {

  private final SubscriptionRepository subscriptionRepository;
  private final BillingQueryRepository queryRepository;
  private final Clock clock;

  public BillingContextService(
      SubscriptionRepository subscriptionRepository,
      BillingQueryRepository queryRepository,
      Clock clock) {
    this.subscriptionRepository = subscriptionRepository;
    this.queryRepository = queryRepository;
    this.clock = clock;
  }

  public Subscription requireSubscription(Long officeId) {
    return subscriptionRepository
        .findByOfficeId(officeId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  public int billableMembers(Long officeId) {
    return Math.max(1, queryRepository.countBillableMembers(officeId));
  }

  public Instant now() {
    return clock.instant();
  }

  public Instant referenceTimeFor(Long officeId) {
    return subscriptionRepository
        .findByOfficeId(officeId)
        .map(this::referenceTime)
        .orElseGet(clock::instant);
  }

  public Instant referenceTime(Subscription subscription) {
    Instant now = clock.instant();
    Instant first = subscription.getFirstBillingScheduledAt();
    return first != null && first.isAfter(now) ? first : now;
  }
}
