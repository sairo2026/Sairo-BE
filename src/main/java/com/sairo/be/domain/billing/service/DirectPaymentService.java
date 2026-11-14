package com.sairo.be.domain.billing.service;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.billing.dto.response.SubscriptionStateResponse;
import com.sairo.be.domain.billing.entity.BillingCustomer;
import com.sairo.be.domain.billing.entity.BillingInvoice;
import com.sairo.be.domain.billing.entity.InvoiceStatus;
import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.entity.SubscriptionStatus;
import com.sairo.be.domain.billing.repository.BillingCustomerRepository;
import com.sairo.be.domain.billing.repository.BillingInvoiceRepository;
import com.sairo.be.domain.billing.repository.BillingQueryRepository;
import com.sairo.be.domain.billing.repository.SubscriptionRepository;
import com.sairo.be.domain.billing.service.BillingChargeService.ChargeOutcome;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.AdvisoryLock;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Profile("!migrate")
@Service
public class DirectPaymentService {

  private static final List<InvoiceStatus> UNPAID =
      List.of(InvoiceStatus.UNBILLED, InvoiceStatus.FAILED);
  private static final List<InvoiceStatus> IN_PROGRESS =
      List.of(InvoiceStatus.PROCESSING, InvoiceStatus.VERIFYING);

  private final BillingContextService context;
  private final SubscriptionRepository subscriptionRepository;
  private final BillingInvoiceRepository invoiceRepository;
  private final BillingCustomerRepository customerRepository;
  private final BillingQueryRepository queryRepository;
  private final InvoiceIssuer invoiceIssuer;
  private final ConsentChecker consentChecker;
  private final BillingChargeService chargeService;
  private final AdvisoryLock advisoryLock;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

  public DirectPaymentService(
      BillingContextService context,
      SubscriptionRepository subscriptionRepository,
      BillingInvoiceRepository invoiceRepository,
      BillingCustomerRepository customerRepository,
      BillingQueryRepository queryRepository,
      InvoiceIssuer invoiceIssuer,
      ConsentChecker consentChecker,
      BillingChargeService chargeService,
      AdvisoryLock advisoryLock,
      TransactionTemplate transactionTemplate,
      Clock clock) {
    this.context = context;
    this.subscriptionRepository = subscriptionRepository;
    this.invoiceRepository = invoiceRepository;
    this.customerRepository = customerRepository;
    this.queryRepository = queryRepository;
    this.invoiceIssuer = invoiceIssuer;
    this.consentChecker = consentChecker;
    this.chargeService = chargeService;
    this.advisoryLock = advisoryLock;
    this.transactionTemplate = transactionTemplate;
    this.clock = clock;
  }

  public SubscriptionStateResponse firstPayment(StaffPrincipal principal) {
    Long invoiceId =
        transactionTemplate.execute(
            status -> {
              Subscription subscription = lockedSubscription(principal.officeId());
              if (subscription.getStatus() != SubscriptionStatus.PRE_PAYMENT) {
                throw new BusinessException(ErrorCode.INVALID_TRANSITION);
              }
              return prepareInvoice(subscription).getId();
            });
    return chargeAndRespond(principal.officeId(), invoiceId);
  }

  public SubscriptionStateResponse retryPayment(StaffPrincipal principal) {
    Long invoiceId =
        transactionTemplate.execute(
            status -> {
              Subscription subscription = lockedSubscription(principal.officeId());
              Instant now = clock.instant();
              SubscriptionStatus current = subscription.getStatus();
              boolean pastDue =
                  current == SubscriptionStatus.PAST_DUE_GRACE
                      || current == SubscriptionStatus.PAST_DUE_RESTRICTED;
              boolean returnPeriod =
                  current == SubscriptionStatus.ENDED
                      && BillingPeriods.isInReturnPeriod(subscription.getEndedAt(), now);
              if (!pastDue && !returnPeriod) {
                throw new BusinessException(ErrorCode.INVALID_TRANSITION);
              }
              return prepareInvoice(subscription).getId();
            });
    return chargeAndRespond(principal.officeId(), invoiceId);
  }

  private Subscription lockedSubscription(Long officeId) {
    Subscription subscription = context.requireSubscription(officeId);
    advisoryLock.lockForTransaction("billing:subscription:" + subscription.getId());
    return subscriptionRepository.findById(subscription.getId()).orElseThrow();
  }

  private BillingInvoice prepareInvoice(Subscription subscription) {
    if (!invoiceRepository
        .findBySubscriptionIdAndStatusIn(subscription.getId(), IN_PROGRESS)
        .isEmpty()) {
      throw new BusinessException(ErrorCode.PAYMENT_VERIFYING);
    }
    boolean hasKey =
        customerRepository
            .findByOfficeId(subscription.getOfficeId())
            .map(BillingCustomer::hasBillingKey)
            .orElse(false);
    if (!hasKey) {
      throw new BusinessException(ErrorCode.PAYMENT_METHOD_REQUIRED);
    }
    Optional<BillingInvoice> reusable =
        invoiceRepository.findBySubscriptionIdAndStatusIn(subscription.getId(), UNPAID).stream()
            .max(
                (first, second) ->
                    first.getBillingPeriodEnd().compareTo(second.getBillingPeriodEnd()));
    if (reusable.isPresent()) {
      if (consentChecker.findValid(subscription.getId(), reusable.get().getAmount()).isEmpty()) {
        throw new BusinessException(ErrorCode.PAYMENT_METHOD_REQUIRED);
      }
      return reusable.get();
    }
    Instant now = clock.instant();
    return invoiceIssuer
        .issue(subscription, now, BillingPeriods.boundary(now, 1), now)
        .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_METHOD_REQUIRED));
  }

  private SubscriptionStateResponse chargeAndRespond(Long officeId, Long invoiceId) {
    ChargeOutcome outcome = chargeService.charge(invoiceId);
    if (outcome.invoiceStatus() == InvoiceStatus.FAILED) {
      throw new BusinessException(ErrorCode.PAYMENT_FAILED);
    }
    return state(officeId);
  }

  public SubscriptionStateResponse state(Long officeId) {
    Subscription subscription = context.requireSubscription(officeId);
    Instant now = clock.instant();
    Optional<Instant> cancellation =
        queryRepository.findActiveCancellationEffectiveAt(subscription.getId());
    boolean inReturn =
        subscription.getStatus() == SubscriptionStatus.ENDED
            && BillingPeriods.isInReturnPeriod(subscription.getEndedAt(), now);
    return new SubscriptionStateResponse(
        subscription.getStatus().name(),
        subscription.getPlan().name(),
        subscription.getStatus() == SubscriptionStatus.TRIAL
            ? toDisplayOffset(subscription.getTrialEndsAt())
            : null,
        toDisplayOffset(subscription.getCurrentPeriodEnd()),
        toDisplayOffset(subscription.getPastDueSince()),
        toDisplayOffset(cancellation.orElse(null)),
        queryRepository.outstandingAmount(officeId),
        inReturn,
        inReturn
            ? toDisplayOffset(BillingPeriods.returnPeriodEnd(subscription.getEndedAt()))
            : null,
        officeId,
        toDisplayOffset(subscription.getCreatedAt()),
        toDisplayOffset(subscription.getUpdatedAt()),
        subscription.getVersion(),
        subscription.getId());
  }
}
