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
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.TimeRules;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
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

  /** 청구 배치의 스케줄러 잠금 최대 유지 시간과 같다. 이 시간이 지난 RUNNING 작업은 다른 실행이 아닌 중단된 실행으로 본다. */
  static final Duration STALE_JOB_AFTER = Duration.ofMinutes(30);

  static final List<Integer> TRIAL_ENDING_DAYS = List.of(1, 7, 14, 30);

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
  private final BillingNoticeDispatcher noticeDispatcher;
  private final BillingReconciliationService reconciliationService;
  private final LiveChargingGuard liveChargingGuard;
  private final LateApprovalRefunds lateApprovalRefunds;

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
      AuthSuspensionService authSuspensionService,
      BillingNoticeDispatcher noticeDispatcher,
      BillingReconciliationService reconciliationService,
      LiveChargingGuard liveChargingGuard,
      LateApprovalRefunds lateApprovalRefunds) {
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
    this.noticeDispatcher = noticeDispatcher;
    this.reconciliationService = reconciliationService;
    this.liveChargingGuard = liveChargingGuard;
    this.lateApprovalRefunds = lateApprovalRefunds;
  }

  public void runAll(Instant now) {
    recoverInterruptedCharges(now);
    runTrialEndingNotices(now);
    runFirstCharges(now);
    runRenewals(now);
    runRetries(now);
    runDelinquency(now);
    runPrePaymentExpiry(now);
    runReconciliation();
    runLateApprovalRefundSweep();
    authSuspensionService.expireSuspensions(now);
    runDailyReconciliation(now);
  }

  /**
   * 프로세스 중단으로 멈춘 청구를 회수한다. 오래된 처리 중 청구서를 확인 중으로 바꿔 결제 결과 확인이 토스 조회로 확정하게 하고, 시작한 뒤 {@link
   * #STALE_JOB_AFTER}가 지나도록 끝나지 않은 청구 작업을 실패로 되돌려 같은 키로 다시 시작하게 한다. 다시 시작한 작업은 확인 중인 청구서를 보면 새 주문을
   * 만들지 않는다.
   */
  void recoverInterruptedCharges(Instant now) {
    try {
      int recovered = chargeService.recoverStaleProcessing(now);
      int reclaimed =
          jobRunService.reclaimStale(RERUNNABLE_CHARGE_JOBS, now.minus(STALE_JOB_AFTER));
      if (recovered > 0 || reclaimed > 0) {
        log.warn("중단된 청구를 회수했습니다. invoices={} jobs={}", recovered, reclaimed);
      }
    } catch (RuntimeException e) {
      log.warn("중단된 청구 회수에 실패했습니다.", e);
    }
  }

  /**
   * 무료 종료 30·14·7·1일 전 안내다. 지금 지난 가장 가까운 기준 하나만 보내고, 같은 구독·종료 시각·기준일은 system_job_run 키로 한 번만 보낸다.
   * 해지를 예약한 구독에는 보내지 않는다.
   */
  public void runTrialEndingNotices(Instant now) {
    Instant until = now.plus(Duration.ofDays(TRIAL_ENDING_DAYS.getLast()));
    for (Subscription subscription :
        subscriptionRepository.findByStatusAndTrialEndsAtGreaterThanAndTrialEndsAtLessThanEqual(
            SubscriptionStatus.TRIAL, now, until)) {
      Instant endsAt = subscription.getTrialEndsAt();
      Duration remaining = Duration.between(now, endsAt);
      Integer days =
          TRIAL_ENDING_DAYS.stream()
              .filter(day -> remaining.compareTo(Duration.ofDays(day)) <= 0)
              .findFirst()
              .orElse(null);
      if (days == null) {
        continue;
      }
      jobRunService.runOnce(
          "billing-trial-ending-notice",
          subscription.getOfficeId(),
          "trial-ending-notice:"
              + subscription.getId()
              + ":"
              + endsAt.getEpochSecond()
              + ":D"
              + days,
          endsAt.minus(Duration.ofDays(days)),
          () -> trialEndingNotice(subscription.getId(), endsAt, days, now));
    }
  }

  private void trialEndingNotice(Long subscriptionId, Instant endsAt, int days, Instant now) {
    transactionTemplate.executeWithoutResult(
        status -> {
          Subscription subscription = lockedSubscription(subscriptionId);
          if (subscription.getStatus() != SubscriptionStatus.TRIAL
              || !endsAt.equals(subscription.getTrialEndsAt())
              || cancellationRepository
                  .findFirstBySubscriptionIdAndRevokedAtIsNullOrderByIdDesc(subscriptionId)
                  .isPresent()) {
            return;
          }
          noticeDispatcher.trialEnding(subscription.getOfficeId(), endsAt, days + "일");
        });
  }

  public void runFirstCharges(Instant now) {
    if (liveChargingGuard.isBlockedForScheduledRun("billing-first-charge")) {
      return;
    }
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
    if (liveChargingGuard.isBlockedForScheduledRun("billing-renewal")) {
      return;
    }
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
    if (liveChargingGuard.isBlockedForScheduledRun("billing-retry")) {
      return;
    }
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

  /**
   * 청구 차단 중에는 재시도도 멈춰 있어 고객이 미납을 해소할 수 없으므로 제한·종료 전이도 함께 멈춘다. 차단이 풀리면 같은 주기에 재시도가 먼저 실행되고, 기간이 이미
   * 지난 구독은 그 뒤에 전이된다.
   */
  public void runDelinquency(Instant now) {
    if (liveChargingGuard.isBlockedForScheduledRun("billing-delinquency")) {
      return;
    }
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
    if (liveChargingGuard.isBlockedForScheduledRun("billing-pre-payment-end")) {
      return;
    }
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

  /** 운영자가 다시 실행할 수 있는 청구 작업 이름이다. 첫 청구, 갱신, 청구 재시도다. */
  public static final List<String> RERUNNABLE_CHARGE_JOBS =
      List.of("billing-first-charge", "billing-renewal", "billing-retry");

  /**
   * 실패한 청구 작업의 원래 멱등키에서 대상을 복원해 다시 실행할 작업을 만든다. 작업은 구독·청구서의 현재 상태를 다시 검사하므로 이미 성공한 결제를 다시 청구하지 않는다.
   * 키를 해석할 수 없으면 비어 있다.
   */
  public Optional<Runnable> rerunWork(String jobName, String idempotencyKey, Instant now) {
    if (!liveChargingGuard.canCharge()) {
      return Optional.empty();
    }
    String[] parts = idempotencyKey.split(":");
    if (parts.length != 3) {
      return Optional.empty();
    }
    long targetId;
    try {
      targetId = Long.parseLong(parts[1]);
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
    return switch (jobName) {
      case "billing-first-charge" ->
          parts[0].equals("first-charge")
              ? Optional.of(() -> firstCharge(targetId, now))
              : Optional.empty();
      case "billing-renewal" ->
          parts[0].equals("renewal") ? Optional.of(() -> renew(targetId, now)) : Optional.empty();
      case "billing-retry" ->
          parts[0].equals("retry")
              ? invoiceRepository
                  .findById(targetId)
                  .map(invoice -> () -> retry(invoice.getId(), invoice.getSubscriptionId()))
              : Optional.empty();
      default -> Optional.empty();
    };
  }

  /** KST 전일 승인·취소 거래의 PG 대사다. 영업일마다 한 번만 만든다. */
  public void runDailyReconciliation(Instant now) {
    reconciliationService.runForPreviousDay(now);
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

  /** 종료한 사무소에 늦게 승인된 결제 가운데 환불 접수가 없는 것을 찾아 접수한다. 실패해도 다음 주기에 다시 시도한다. */
  public void runLateApprovalRefundSweep() {
    try {
      int created = lateApprovalRefunds.sweep();
      if (created > 0) {
        log.warn("종료한 사무소의 늦은 승인 결제 환불을 접수했습니다. count={}", created);
      }
    } catch (RuntimeException e) {
      log.warn("종료한 사무소의 늦은 승인 결제 환불 접수 점검에 실패했습니다.", e);
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
              Optional<BillingInvoice> issued =
                  invoiceRepository.findBySubscriptionIdAndBillingPeriodStart(
                      subscriptionId, scheduled);
              if (issued.isPresent()) {
                return resumableInvoiceId(issued.get());
              }
              Optional<BillingInvoice> invoice =
                  hasBillingKey(subscription)
                      ? invoiceIssuer.issue(
                          subscription, scheduled, BillingPeriods.boundary(scheduled, 1), scheduled)
                      : Optional.empty();
              if (invoice.isEmpty()) {
                subscription.enterPrePayment(now);
                subscriptionRepository.save(subscription);
                noticeDispatcher.prePaymentStarted(
                    subscription.getOfficeId(), now.plus(BillingPeriods.PRE_PAYMENT_LIMIT));
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
      if (e.getErrorCode() == ErrorCode.BILLING_AMOUNT_UNSUPPORTED) {
        throw e;
      }
      log.warn("첫 청구를 건너뜁니다. subscriptionId={} code={}", subscriptionId, e.getErrorCode());
    }
  }

  /**
   * 같은 청구기간의 청구서가 이미 있으면 새로 만들지 않는다. 아직 청구하지 않은 청구서만 이어서 청구하고, 처리 중·확인 중·실패·완료는 각자의 확인·재시도 경로가 맡으므로
   * 청구하지 않는다.
   */
  private static Long resumableInvoiceId(BillingInvoice invoice) {
    return invoice.getStatus() == InvoiceStatus.UNBILLED ? invoice.getId() : null;
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
              Optional<BillingInvoice> issued =
                  invoiceRepository.findBySubscriptionIdAndBillingPeriodStart(
                      subscriptionId, periodStart);
              if (issued.isPresent()) {
                return resumableInvoiceId(issued.get());
              }
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
                if (hasBillingKey(subscription)) {
                  noticeDispatcher.renewalNotProcessedNoConsent(subscription.getOfficeId());
                }
                return null;
              }
              return invoice.get().getId();
            });
    if (invoiceId != null) {
      try {
        chargeService.charge(invoiceId);
      } catch (BusinessException e) {
        if (e.getErrorCode() == ErrorCode.BILLING_AMOUNT_UNSUPPORTED) {
          throw e;
        }
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
        if (e.getErrorCode() == ErrorCode.BILLING_AMOUNT_UNSUPPORTED) {
          throw e;
        }
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
            Instant since = subscription.getPastDueSince();
            noticeDispatcher.suspensionStarted(
                subscription.getOfficeId(),
                since.plus(BillingPeriods.PAST_DUE_RESTRICT_AFTER),
                since.plus(BillingPeriods.PAST_DUE_TERMINATE_AFTER));
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
          noticeDispatcher.endedUnpaid(
              subscription.getOfficeId(), endedAt, BillingPeriods.returnPeriodEnd(endedAt));
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
    long months =
        Math.max(
            0,
            ChronoUnit.MONTHS.between(
                YearMonth.from(TimeRules.toDisplay(base)),
                YearMonth.from(TimeRules.toDisplay(start))));
    Instant boundary = BillingPeriods.boundary(base, months);
    while (!boundary.isAfter(start)) {
      boundary = BillingPeriods.boundary(base, ++months);
    }
    return boundary;
  }
}
