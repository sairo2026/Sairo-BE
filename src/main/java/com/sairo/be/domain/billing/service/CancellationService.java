package com.sairo.be.domain.billing.service;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.billing.dto.response.SubscriptionCancelResponse;
import com.sairo.be.domain.billing.dto.response.SubscriptionCancelWithdrawResponse;
import com.sairo.be.domain.billing.entity.BillingInvoice;
import com.sairo.be.domain.billing.entity.InvoiceStatus;
import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.entity.SubscriptionCancellation;
import com.sairo.be.domain.billing.repository.BillingInvoiceRepository;
import com.sairo.be.domain.billing.repository.SubscriptionCancellationRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StepUpState;
import com.sairo.be.global.support.AdvisoryLock;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class CancellationService {

  private static final List<InvoiceStatus> OPEN_INVOICES =
      List.of(InvoiceStatus.UNBILLED, InvoiceStatus.FAILED);

  private final BillingContextService context;
  private final SubscriptionCancellationRepository cancellationRepository;
  private final BillingInvoiceRepository invoiceRepository;
  private final ConsentWithdrawals consentWithdrawals;
  private final StepUpState stepUpState;
  private final AdvisoryLock advisoryLock;
  private final BillingNoticeDispatcher noticeDispatcher;
  private final Clock clock;

  public CancellationService(
      BillingContextService context,
      SubscriptionCancellationRepository cancellationRepository,
      BillingInvoiceRepository invoiceRepository,
      ConsentWithdrawals consentWithdrawals,
      StepUpState stepUpState,
      AdvisoryLock advisoryLock,
      BillingNoticeDispatcher noticeDispatcher,
      Clock clock) {
    this.context = context;
    this.cancellationRepository = cancellationRepository;
    this.invoiceRepository = invoiceRepository;
    this.consentWithdrawals = consentWithdrawals;
    this.stepUpState = stepUpState;
    this.advisoryLock = advisoryLock;
    this.noticeDispatcher = noticeDispatcher;
    this.clock = clock;
  }

  @Transactional
  public SubscriptionCancelResponse cancel(StaffPrincipal principal, HttpSession session) {
    stepUpState.require(session);
    Subscription subscription = context.requireSubscription(principal.officeId());
    advisoryLock.lockForTransaction("billing:subscription:" + subscription.getId());
    Instant now = clock.instant();
    if (cancellationRepository
        .findFirstBySubscriptionIdAndRevokedAtIsNullOrderByIdDesc(subscription.getId())
        .filter(reserved -> !reserved.isEffective(now))
        .isPresent()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant effectiveAt =
        switch (subscription.getStatus()) {
          case ACTIVE -> subscription.getCurrentPeriodEnd();
          case TRIAL -> subscription.getTrialEndsAt();
          case PRE_PAYMENT, PAST_DUE_GRACE, PAST_DUE_RESTRICTED -> now;
          case SUSPENDED, ENDED -> throw new BusinessException(ErrorCode.INVALID_TRANSITION);
        };
    cancellationRepository.save(
        SubscriptionCancellation.request(
            subscription.getId(), principal.membershipId(), now, effectiveAt));
    if (!effectiveAt.isAfter(now)) {
      endNow(subscription, now);
    } else {
      noticeDispatcher.cancellationScheduled(principal.officeId(), effectiveAt);
    }
    subscription.markEventRecorded(now);
    stepUpState.consume(session);
    return new SubscriptionCancelResponse(toDisplayOffset(effectiveAt), null);
  }

  @Transactional
  public SubscriptionCancelWithdrawResponse withdraw(StaffPrincipal principal) {
    Subscription subscription = context.requireSubscription(principal.officeId());
    advisoryLock.lockForTransaction("billing:subscription:" + subscription.getId());
    Instant now = clock.instant();
    Optional<SubscriptionCancellation> active =
        cancellationRepository.findFirstBySubscriptionIdAndRevokedAtIsNullOrderByIdDesc(
            subscription.getId());
    if (active.isEmpty()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    if (active.get().isEffective(now)) {
      throw new BusinessException(ErrorCode.CANCELLATION_ALREADY_EFFECTIVE);
    }
    active.get().revoke(now);
    cancellationRepository.save(active.get());
    subscription.markEventRecorded(now);
    return new SubscriptionCancelWithdrawResponse(true, toDisplayOffset(now));
  }

  public void endNow(Subscription subscription, Instant now) {
    List<BillingInvoice> open =
        invoiceRepository.findBySubscriptionIdAndStatusIn(subscription.getId(), OPEN_INVOICES);
    open.forEach(BillingInvoice::markCancelled);
    invoiceRepository.saveAll(open);
    consentWithdrawals.record(subscription, now);
    subscription.end(now);
  }
}
