package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.entity.BillingInvoice;
import com.sairo.be.domain.billing.entity.InvoiceStatus;
import com.sairo.be.domain.billing.entity.RefundEventType;
import com.sairo.be.domain.billing.entity.RefundReasonCode;
import com.sairo.be.domain.billing.entity.RefundRequest;
import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.entity.SubscriptionStatus;
import com.sairo.be.domain.billing.mapper.BillingMapper;
import com.sairo.be.domain.billing.repository.BillingInvoiceRepository;
import com.sairo.be.domain.billing.repository.BillingQueryRepository;
import com.sairo.be.domain.billing.repository.RefundRequestRepository;
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
  private final RefundRequestRepository refundRepository;
  private final BillingQueryRepository queryRepository;
  private final ConsentWithdrawals consentWithdrawals;
  private final AdvisoryLock advisoryLock;

  public AuthSuspensionService(
      SubscriptionRepository subscriptionRepository,
      BillingInvoiceRepository invoiceRepository,
      RefundRequestRepository refundRepository,
      BillingQueryRepository queryRepository,
      ConsentWithdrawals consentWithdrawals,
      AdvisoryLock advisoryLock) {
    this.subscriptionRepository = subscriptionRepository;
    this.invoiceRepository = invoiceRepository;
    this.refundRepository = refundRepository;
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
    requestRemainingPeriodRefund(subscription, now);
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

  private void requestRemainingPeriodRefund(Subscription subscription, Instant now) {
    invoiceRepository
        .findBySubscriptionIdAndStatusIn(
            subscription.getId(), List.of(InvoiceStatus.PAID, InvoiceStatus.PARTIALLY_REFUNDED))
        .stream()
        .filter(invoice -> invoice.getBillingPeriodStart() != null)
        .filter(invoice -> invoice.getBillingPeriodEnd().isAfter(now))
        .filter(invoice -> !invoice.getBillingPeriodStart().isAfter(now))
        .max((first, second) -> first.getBillingPeriodEnd().compareTo(second.getBillingPeriodEnd()))
        .ifPresent(
            invoice -> {
              if (refundRepository
                  .findByInvoiceIdAndReasonCode(invoice.getId(), RefundReasonCode.AUTH_SUSPENSION)
                  .isPresent()) {
                return;
              }
              long total =
                  Duration.between(invoice.getBillingPeriodStart(), invoice.getBillingPeriodEnd())
                      .toSeconds();
              long remaining =
                  Math.max(0, Duration.between(now, invoice.getBillingPeriodEnd()).toSeconds());
              long entitlement = Math.floorDiv(invoice.getAmount() * remaining, total);
              long completed = refundRepository.sumCompleted(invoice.getId());
              long reserved = refundRepository.sumReserved(invoice.getId());
              long previousSameEvent =
                  refundRepository.sumCompletedByEventType(
                      invoice.getId(), RefundEventType.AUTH_SUSPENSION);
              long refundable = BillingMapper.refundableAmount(invoice, completed, reserved);
              long refundAmount =
                  Math.max(0, Math.min(entitlement - previousSameEvent, refundable));
              RefundRequest request =
                  RefundRequest.receive(
                      invoice.getOfficeId(),
                      invoice.getId(),
                      RefundReasonCode.AUTH_SUSPENSION,
                      null,
                      now);
              request.fixCalculation(
                  invoice.getAmount(),
                  total,
                  entitlement,
                  previousSameEvent,
                  reserved,
                  refundAmount);
              request.recordImmediateEnd(remaining, now);
              refundRepository.save(request);
            });
  }
}
