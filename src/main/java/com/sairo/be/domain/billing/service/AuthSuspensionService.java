package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.entity.BillingInvoice;
import com.sairo.be.domain.billing.entity.InvoiceStatus;
import com.sairo.be.domain.billing.entity.RefundReasonCode;
import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.entity.SubscriptionStatus;
import com.sairo.be.domain.billing.repository.BillingInvoiceRepository;
import com.sairo.be.domain.billing.repository.BillingQueryRepository;
import com.sairo.be.domain.billing.repository.SubscriptionRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.support.AdvisoryLock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class AuthSuspensionService {

  public static final Duration REAUTH_WINDOW = Duration.ofDays(30);

  private static final List<InvoiceStatus> UNPAID =
      List.of(InvoiceStatus.UNBILLED, InvoiceStatus.FAILED);

  private final SubscriptionRepository subscriptionRepository;
  private final BillingInvoiceRepository invoiceRepository;
  private final RemainingPeriodRefunds remainingPeriodRefunds;
  private final BillingQueryRepository queryRepository;
  private final ConsentWithdrawals consentWithdrawals;
  private final AdvisoryLock advisoryLock;

  public AuthSuspensionService(
      SubscriptionRepository subscriptionRepository,
      BillingInvoiceRepository invoiceRepository,
      RemainingPeriodRefunds remainingPeriodRefunds,
      BillingQueryRepository queryRepository,
      ConsentWithdrawals consentWithdrawals,
      AdvisoryLock advisoryLock) {
    this.subscriptionRepository = subscriptionRepository;
    this.invoiceRepository = invoiceRepository;
    this.remainingPeriodRefunds = remainingPeriodRefunds;
    this.queryRepository = queryRepository;
    this.consentWithdrawals = consentWithdrawals;
    this.advisoryLock = advisoryLock;
  }

  @Transactional
  public void suspend(Long officeId, Instant now) {
    Optional<Subscription> found = subscriptionRepository.findByOfficeId(officeId);
    if (found.isEmpty()) {
      return;
    }
    advisoryLock.lockForTransaction("billing:subscription:" + found.get().getId());
    Subscription subscription = subscriptionRepository.findById(found.get().getId()).orElseThrow();
    SubscriptionStatus status = subscription.getStatus();
    if (status == SubscriptionStatus.SUSPENDED || status == SubscriptionStatus.ENDED) {
      return;
    }
    Long remainingTrialSeconds = remainingTrialSeconds(subscription, now);
    List<BillingInvoice> unpaid =
        invoiceRepository.findBySubscriptionIdAndStatusIn(subscription.getId(), UNPAID);
    unpaid.forEach(BillingInvoice::markCancelled);
    invoiceRepository.saveAll(unpaid);
    remainingPeriodRefunds.request(subscription, now, RefundReasonCode.AUTH_SUSPENSION);
    consentWithdrawals.record(subscription, now);
    subscription.suspend(now, remainingTrialSeconds);
    subscriptionRepository.save(subscription);
  }

  @Transactional
  public Subscription resume(Long officeId, Instant now) {
    Subscription found =
        subscriptionRepository
            .findByOfficeId(officeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    advisoryLock.lockForTransaction("billing:subscription:" + found.getId());
    Subscription subscription = subscriptionRepository.findById(found.getId()).orElseThrow();
    if (subscription.getStatus() != SubscriptionStatus.SUSPENDED
        || !now.isBefore(subscription.getSuspendedAt().plus(REAUTH_WINDOW))) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Long remaining = subscription.getTrialRemainingSeconds();
    if (remaining != null && remaining > 0 && subscription.getTrialEventId() != null) {
      subscription.resumeTrial(now.plusSeconds(remaining));
    } else {
      subscription.resumeToPrePayment(now);
    }
    return subscriptionRepository.save(subscription);
  }

  @Transactional
  public int expireSuspensions(Instant now) {
    int ended = 0;
    for (Subscription subscription :
        subscriptionRepository.findByStatusIn(List.of(SubscriptionStatus.SUSPENDED))) {
      Instant deadline = subscription.getSuspendedAt().plus(REAUTH_WINDOW);
      if (!now.isBefore(deadline)) {
        advisoryLock.lockForTransaction("billing:subscription:" + subscription.getId());
        subscription.end(deadline);
        subscriptionRepository.save(subscription);
        ended++;
      }
    }
    return ended;
  }

  private Long remainingTrialSeconds(Subscription subscription, Instant now) {
    if (subscription.getStatus() != SubscriptionStatus.TRIAL
        || subscription.getTrialEndsAt() == null
        || subscription.getTrialEventId() == null) {
      return null;
    }
    boolean fixedEnd =
        queryRepository.findTrialEventFixedEnd(subscription.getTrialEventId()).isPresent();
    if (fixedEnd) {
      return null;
    }
    return Math.max(0, Duration.between(now, subscription.getTrialEndsAt()).toSeconds());
  }
}
