package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.entity.BillingInvoice;
import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.entity.SubscriptionStatus;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class SubscriptionTransitions {

  public void onPaid(Subscription subscription, BillingInvoice invoice) {
    Instant start = invoice.getBillingPeriodStart();
    Instant end = invoice.getBillingPeriodEnd();
    switch (subscription.getStatus()) {
      case PRE_PAYMENT, TRIAL, ENDED -> subscription.activate(start, end, start);
      case PAST_DUE_GRACE, PAST_DUE_RESTRICTED, ACTIVE -> subscription.activate(start, end, null);
      case SUSPENDED -> {}
    }
  }

  public void onFailed(Subscription subscription, BillingInvoice invoice, Instant now) {
    SubscriptionStatus status = subscription.getStatus();
    if (status == SubscriptionStatus.TRIAL) {
      subscription.end(now);
    } else if (status == SubscriptionStatus.ACTIVE) {
      subscription.startPastDue(invoice.getDueAt());
    }
  }

  public boolean isPastDue(Subscription subscription) {
    return subscription.getStatus() == SubscriptionStatus.PAST_DUE_GRACE
        || subscription.getStatus() == SubscriptionStatus.PAST_DUE_RESTRICTED;
  }
}
