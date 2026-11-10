package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.entity.BillingInvoice;
import com.sairo.be.domain.billing.entity.InvoiceStatus;
import com.sairo.be.domain.billing.entity.PriceChangeDecision;
import com.sairo.be.domain.billing.entity.PriceChangeNotice;
import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.entity.SubscriptionCancellation;
import com.sairo.be.domain.billing.entity.SubscriptionStatus;
import com.sairo.be.domain.billing.repository.BillingCustomerRepository;
import com.sairo.be.domain.billing.repository.BillingInvoiceRepository;
import com.sairo.be.domain.billing.repository.PriceChangeNoticeRepository;
import com.sairo.be.domain.billing.repository.SubscriptionCancellationRepository;
import com.sairo.be.domain.billing.repository.SubscriptionRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.support.AdvisoryLock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Profile("!migrate")
@Service
public class BillingJobService {

  private static final List<InvoiceStatus> UNPAID =
      List.of(InvoiceStatus.UNBILLED, InvoiceStatus.FAILED);
  private static final int MAX_BOUNDARY_STEPS = 1200;

  private final SubscriptionRepository subscriptionRepository;
  private final BillingInvoiceRepository invoiceRepository;
  private final BillingCustomerRepository customerRepository;
  private final SubscriptionCancellationRepository cancellationRepository;
  private final PriceChangeNoticeRepository noticeRepository;
  private final InvoiceIssuer invoiceIssuer;
  private final ConsentChecker consentChecker;
  private final ConsentWithdrawals consentWithdrawals;
  private final BillingChargeService chargeService;
  private final SystemJobRunService jobRunService;
  private final AdvisoryLock advisoryLock;
  private final TransactionTemplate transactionTemplate;
  private final AuthSuspensionService authSuspensionService;

  public BillingJobService(
      SubscriptionRepository subscriptionRepository,
      BillingInvoiceRepository invoiceRepository,
      BillingCustomerRepository customerRepository,
      SubscriptionCancellationRepository cancellationRepository,
      PriceChangeNoticeRepository noticeRepository,
      InvoiceIssuer invoiceIssuer,
      ConsentChecker consentChecker,
      ConsentWithdrawals consentWithdrawals,
      BillingChargeService chargeService,
      SystemJobRunService jobRunService,
      AdvisoryLock advisoryLock,
      TransactionTemplate transactionTemplate,
      AuthSuspensionService authSuspensionService) {
    this.subscriptionRepository = subscriptionRepository;
    this.invoiceRepository = invoiceRepository;
    this.customerRepository = customerRepository;
    this.cancellationRepository = cancellationRepository;
    this.noticeRepository = noticeRepository;
    this.invoiceIssuer = invoiceIssuer;
    this.consentChecker = consentChecker;
    this.consentWithdrawals = consentWithdrawals;
    this.chargeService = chargeService;
    this.jobRunService = jobRunService;
    this.advisoryLock = advisoryLock;
    this.transactionTemplate = transactionTemplate;
    this.authSuspensionService = authSuspensionService;
  }

  public void runAll(Instant now) {
    runFirstCharges(now);
    runRenewals(now);
    runRetries(now);
    runDelinquency(now);
    runPrePaymentExpiry(now);
    runReconciliation();
    authSuspensionService.expireSuspensions(now);
  }

  public void runFirstCharges(Instant now) {
    for (Subscription subscription :
        subscriptionRepository.findByStatusAndFirstBillingScheduledAtLessThanEqual(
            SubscriptionStatus.TRIAL, now)) {
      String key =
          "first-charge:"
              + subscription.getId()
              + ":"
              + subscription.getFirstBillingScheduledAt().getEpochSecond();
      jobRunService.runOnce(
          "billing-first-charge",
          subscription.getOfficeId(),
          key,
          subscription.getFirstBillingScheduledAt(),
          () -> firstCharge(subscription.getId(), now));
    }
  }

  public void runRenewals(Instant now) {
    for (Subscription subscription :
        subscriptionRepository.findByStatusAndCurrentPeriodEndLessThanEqual(
            SubscriptionStatus.ACTIVE, now)) {
      String key =
          "renewal:"
              + subscription.getId()
              + ":"
              + subscription.getCurrentPeriodEnd().getEpochSecond();
      jobRunService.runOnce(
          "billing-renewal",
          subscription.getOfficeId(),
          key,
          subscription.getCurrentPeriodEnd(),
          () -> renew(subscription.getId(), now));
    }
  }

  public void runRetries(Instant now) {
    for (BillingInvoice invoice :
        invoiceRepository.findByStatusAndNextRetryAtLessThanEqual(InvoiceStatus.FAILED, now)) {
      String key = "retry:" + invoice.getId() + ":" + invoice.getNextRetryAt().getEpochSecond();
      jobRunService.runOnce(
          "billing-retry",
          invoice.getOfficeId(),
          key,
          invoice.getNextRetryAt(),
          () -> retry(invoice.getId(), invoice.getSubscriptionId()));
    }
  }

  public void runDelinquency(Instant now) {
    for (Subscription subscription :
        subscriptionRepository.findByStatusIn(
            List.of(SubscriptionStatus.PAST_DUE_GRACE, SubscriptionStatus.PAST_DUE_RESTRICTED))) {
      Instant since = subscription.getPastDueSince();
      if (since == null) {
        continue;
      }
      if (!now.isBefore(since.plus(BillingPeriods.PAST_DUE_TERMINATE_AFTER))) {
        jobRunService.runOnce(
            "billing-delinquency-end",
            subscription.getOfficeId(),
            "delinquency-end:" + subscription.getId() + ":" + since.getEpochSecond(),
            since,
            () ->
                endUnpaid(
                    subscription.getId(),
                    SubscriptionStatus.PAST_DUE_GRACE,
                    SubscriptionStatus.PAST_DUE_RESTRICTED,
                    since.plus(BillingPeriods.PAST_DUE_TERMINATE_AFTER)));
      } else if (subscription.getStatus() == SubscriptionStatus.PAST_DUE_GRACE
          && !now.isBefore(since.plus(BillingPeriods.PAST_DUE_RESTRICT_AFTER))) {
        jobRunService.runOnce(
            "billing-delinquency-restrict",
            subscription.getOfficeId(),
            "delinquency-restrict:" + subscription.getId() + ":" + since.getEpochSecond(),
            since,
            () -> restrict(subscription.getId()));
      }
    }
  }

  public void runPrePaymentExpiry(Instant now) {
    for (Subscription subscription :
        subscriptionRepository.findByStatusAndPrePaymentSinceLessThanEqual(
            SubscriptionStatus.PRE_PAYMENT, now.minus(BillingPeriods.PRE_PAYMENT_LIMIT))) {
      Instant since = subscription.getPrePaymentSince();
      jobRunService.runOnce(
          "billing-pre-payment-end",
          subscription.getOfficeId(),
          "pre-payment-end:" + subscription.getId() + ":" + since.getEpochSecond(),
          since,
          () ->
              endUnpaid(
                  subscription.getId(),
                  SubscriptionStatus.PRE_PAYMENT,
                  SubscriptionStatus.PRE_PAYMENT,
                  since.plus(BillingPeriods.PRE_PAYMENT_LIMIT)));
    }
  }

  public void runReconciliation() {
    for (BillingInvoice invoice : invoiceRepository.findByStatus(InvoiceStatus.VERIFYING)) {
      try {
        chargeService.reconcile(invoice.getId());
      } catch (RuntimeException e) {
        log.warn("결제 결과 확인에 실패했습니다. invoiceId={}", invoice.getId(), e);
      }
    }
  }

  private void firstCharge(Long subscriptionId, Instant now) {
    Long invoiceId =
        transactionTemplate.execute(
            status -> {
              Subscription subscription = lockedSubscription(subscriptionId);
              if (subscription.getStatus() != SubscriptionStatus.TRIAL) {
                return null;
              }
              Instant scheduled = subscription.getFirstBillingScheduledAt();
              Optional<SubscriptionCancellation> cancellation =
                  effectiveCancellation(subscription, now);
              if (cancellation.isPresent()) {
                consentWithdrawals.record(subscription, now);
                subscription.end(cancellation.get().getEffectiveAt());
                subscriptionRepository.save(subscription);
                return null;
              }
              Optional<BillingInvoice> invoice =
                  hasBillingKey(subscription)
                      ? invoiceIssuer.issue(
                          subscription, scheduled, BillingPeriods.boundary(scheduled, 1), scheduled)
                      : Optional.empty();
              if (invoice.isEmpty()) {
                subscription.enterPrePayment(now);
                subscriptionRepository.save(subscription);
                return null;
              }
              return invoice.get().getId();
            });
    if (invoiceId == null) {
      return;
    }
    try {
      var outcome = chargeService.charge(invoiceId);
      if (outcome.isVerifying()) {
        transactionTemplate.executeWithoutResult(
            status -> {
              Subscription subscription = lockedSubscription(subscriptionId);
              if (subscription.getStatus() == SubscriptionStatus.TRIAL) {
                subscription.end(now);
                subscriptionRepository.save(subscription);
              }
            });
      }
    } catch (BusinessException e) {
      log.warn("첫 청구를 건너뜁니다. subscriptionId={} code={}", subscriptionId, e.getErrorCode());
    }
  }

  private void renew(Long subscriptionId, Instant now) {
    Long invoiceId =
        transactionTemplate.execute(
            status -> {
              Subscription subscription = lockedSubscription(subscriptionId);
              if (subscription.getStatus() != SubscriptionStatus.ACTIVE
                  || subscription.getCurrentPeriodEnd().isAfter(now)) {
                return null;
              }
              Optional<SubscriptionCancellation> cancellation =
                  effectiveCancellation(subscription, now);
              if (cancellation.isPresent()) {
                consentWithdrawals.record(subscription, now);
                subscription.end(cancellation.get().getEffectiveAt());
                subscriptionRepository.save(subscription);
                return null;
              }
              Instant periodStart = subscription.getCurrentPeriodEnd();
              if (endsWithoutPriceConsent(subscription, periodStart, now)) {
                return null;
              }
              Instant periodEnd = nextBoundary(subscription.getCycleAnchorAt(), periodStart);
              Optional<BillingInvoice> invoice =
                  hasBillingKey(subscription)
                      ? invoiceIssuer.issue(subscription, periodStart, periodEnd, periodStart)
                      : Optional.empty();
              if (invoice.isEmpty()) {
                subscription.startPastDue(periodStart);
                subscriptionRepository.save(subscription);
                return null;
              }
              return invoice.get().getId();
            });
    if (invoiceId != null) {
      try {
        chargeService.charge(invoiceId);
      } catch (BusinessException e) {
        log.warn("갱신 청구를 건너뜁니다. subscriptionId={} code={}", subscriptionId, e.getErrorCode());
      }
    }
  }

  private void retry(Long invoiceId, Long subscriptionId) {
    boolean eligible =
        Boolean.TRUE.equals(
            transactionTemplate.execute(
                status -> {
                  Subscription subscription = lockedSubscription(subscriptionId);
                  BillingInvoice invoice = invoiceRepository.findById(invoiceId).orElseThrow();
                  return invoice.getStatus() == InvoiceStatus.FAILED
                      && (subscription.getStatus() == SubscriptionStatus.PAST_DUE_GRACE
                          || subscription.getStatus() == SubscriptionStatus.PAST_DUE_RESTRICTED)
                      && consentChecker.findValid(subscriptionId, invoice.getAmount()).isPresent();
                }));
    if (eligible) {
      try {
        chargeService.charge(invoiceId);
      } catch (BusinessException e) {
        log.warn("재시도를 건너뜁니다. invoiceId={} code={}", invoiceId, e.getErrorCode());
      }
    }
  }

  private void restrict(Long subscriptionId) {
    transactionTemplate.executeWithoutResult(
        status -> {
          Subscription subscription = lockedSubscription(subscriptionId);
          if (subscription.getStatus() == SubscriptionStatus.PAST_DUE_GRACE) {
            subscription.restrictPastDue();
            subscriptionRepository.save(subscription);
          }
        });
  }

  private void endUnpaid(
      Long subscriptionId,
      SubscriptionStatus firstStatus,
      SubscriptionStatus secondStatus,
      Instant endedAt) {
    transactionTemplate.executeWithoutResult(
        status -> {
          Subscription subscription = lockedSubscription(subscriptionId);
          if (subscription.getStatus() != firstStatus && subscription.getStatus() != secondStatus) {
            return;
          }
          List<BillingInvoice> open =
              invoiceRepository.findBySubscriptionIdAndStatusIn(subscriptionId, UNPAID);
          open.forEach(BillingInvoice::markCancelled);
          invoiceRepository.saveAll(open);
          subscription.end(endedAt);
          subscriptionRepository.save(subscription);
        });
  }

  private boolean endsWithoutPriceConsent(
      Subscription subscription, Instant periodStart, Instant now) {
    Optional<PriceChangeNotice> notice =
        noticeRepository.findBySubscriptionIdAndAppliesFromPeriodStart(
            subscription.getId(), periodStart);
    if (notice.isEmpty()
        || !notice.get().isConsentRequired()
        || notice.get().getDecision() == PriceChangeDecision.AGREED) {
      return false;
    }
    if (notice.get().getDecision() == PriceChangeDecision.PENDING) {
      notice.get().endWithoutConsent(now);
      noticeRepository.save(notice.get());
    }
    List<BillingInvoice> open =
        invoiceRepository.findBySubscriptionIdAndStatusIn(subscription.getId(), UNPAID);
    open.forEach(BillingInvoice::markCancelled);
    invoiceRepository.saveAll(open);
    subscription.end(periodStart);
    subscriptionRepository.save(subscription);
    return true;
  }

  private Optional<SubscriptionCancellation> effectiveCancellation(
      Subscription subscription, Instant now) {
    return cancellationRepository
        .findFirstBySubscriptionIdAndRevokedAtIsNullOrderByIdDesc(subscription.getId())
        .filter(cancellation -> cancellation.isEffective(now));
  }

  private boolean hasBillingKey(Subscription subscription) {
    return customerRepository
        .findByOfficeId(subscription.getOfficeId())
        .map(customer -> customer.hasBillingKey())
        .orElse(false);
  }

  private Subscription lockedSubscription(Long subscriptionId) {
    advisoryLock.lockForTransaction("billing:subscription:" + subscriptionId);
    return subscriptionRepository.findById(subscriptionId).orElseThrow();
  }

  static Instant nextBoundary(Instant anchor, Instant start) {
    Instant base = anchor == null ? start : anchor;
    for (int step = 0; step <= MAX_BOUNDARY_STEPS; step++) {
      Instant boundary = BillingPeriods.boundary(base, step);
      if (boundary.isAfter(start)) {
        return boundary;
      }
    }
    return BillingPeriods.boundary(start, 1);
  }
}
