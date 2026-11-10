package com.sairo.be.domain.billing.service;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.auth.entity.TermsAgreement;
import com.sairo.be.domain.auth.repository.TermsAgreementRepository;
import com.sairo.be.domain.billing.dto.response.SubscriptionCurrentResponse;
import com.sairo.be.domain.billing.entity.BillingCustomer;
import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.entity.SubscriptionPurchaseConsent;
import com.sairo.be.domain.billing.entity.SubscriptionStatus;
import com.sairo.be.domain.billing.repository.BillingCustomerRepository;
import com.sairo.be.domain.billing.repository.BillingQueryRepository;
import com.sairo.be.domain.billing.repository.BillingQueryRepository.ApprovedAgreementRow;
import com.sairo.be.domain.billing.repository.BillingQueryRepository.LatestInvoiceRow;
import com.sairo.be.domain.billing.repository.SubscriptionPurchaseConsentRepository;
import com.sairo.be.domain.billing.service.PriceScheduleSelector.PriceSelection;
import com.sairo.be.domain.legal.service.TermsCatalogService;
import com.sairo.be.domain.legal.service.TermsVersionView;
import com.sairo.be.global.security.StaffPrincipal;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
@Transactional(readOnly = true)
public class SubscriptionCurrentService {

  public static final String PURCHASE_TERMS_CODE = "SUBSCRIPTION_PURCHASE";

  private final BillingContextService context;
  private final PriceScheduleSelector priceSelector;
  private final BillingQueryRepository queryRepository;
  private final BillingCustomerRepository customerRepository;
  private final SubscriptionPurchaseConsentRepository consentRepository;
  private final TermsAgreementRepository termsAgreementRepository;
  private final TermsCatalogService termsCatalogService;

  public SubscriptionCurrentService(
      BillingContextService context,
      PriceScheduleSelector priceSelector,
      BillingQueryRepository queryRepository,
      BillingCustomerRepository customerRepository,
      SubscriptionPurchaseConsentRepository consentRepository,
      TermsAgreementRepository termsAgreementRepository,
      TermsCatalogService termsCatalogService) {
    this.context = context;
    this.priceSelector = priceSelector;
    this.queryRepository = queryRepository;
    this.customerRepository = customerRepository;
    this.consentRepository = consentRepository;
    this.termsAgreementRepository = termsAgreementRepository;
    this.termsCatalogService = termsCatalogService;
  }

  public SubscriptionCurrentResponse current(StaffPrincipal principal) {
    Long officeId = principal.officeId();
    Subscription subscription = context.requireSubscription(officeId);
    Instant now = context.now();
    int memberCount = context.billableMembers(officeId);
    Optional<PriceSelection> selection =
        priceSelector.find(officeId, context.referenceTime(subscription));
    Optional<Instant> cancellation =
        queryRepository.findActiveCancellationEffectiveAt(subscription.getId());
    SubscriptionStatus status = subscription.getStatus();

    Instant nextChargeAt = nextChargeAt(subscription, cancellation.isPresent());
    Long nextChargeAmount =
        nextChargeAt == null
            ? null
            : priceSelector
                .find(officeId, nextChargeAt)
                .map(found -> PriceCalculator.monthlyAmount(found.table(), memberCount))
                .orElse(null);

    boolean pastDue =
        status == SubscriptionStatus.PAST_DUE_GRACE
            || status == SubscriptionStatus.PAST_DUE_RESTRICTED;
    boolean inReturnPeriod =
        status == SubscriptionStatus.ENDED
            && BillingPeriods.isInReturnPeriod(subscription.getEndedAt(), now);
    boolean retryTarget = pastDue || inReturnPeriod;

    Optional<BillingCustomer> customer = customerRepository.findByOfficeId(officeId);
    boolean hasBillingKey = customer.map(BillingCustomer::hasBillingKey).orElse(false);
    SubscriptionCurrentResponse.PaymentMethod paymentMethod =
        hasBillingKey
            ? new SubscriptionCurrentResponse.PaymentMethod(
                customer.get().getCardIssuer(),
                customer.get().getCardLast4(),
                toDisplayOffset(customer.get().getBillingKeyRegisteredAt()))
            : null;

    Optional<LatestInvoiceRow> latest = queryRepository.findLatestInvoice(officeId);
    Optional<ApprovedAgreementRow> agreement = queryRepository.findApprovedAgreement(officeId);

    return new SubscriptionCurrentResponse(
        status.name(),
        subscription.getPlan().name(),
        memberCount,
        selection.map(PriceSelection::version).orElse(null),
        agreement.map(this::toStartCustomer).orElse(null),
        queryRepository.countPendingPriceChangeNotices(officeId),
        status == SubscriptionStatus.TRIAL ? toDisplayOffset(subscription.getTrialEndsAt()) : null,
        toDisplayOffset(currentPeriodEnd(subscription)),
        toDisplayOffset(nextChargeAt),
        nextChargeAmount,
        toDisplayOffset(subscription.getFirstBillingScheduledAt()),
        toDisplayOffset(subscription.getPrePaymentSince()),
        pastDue ? toDisplayOffset(subscription.getPastDueSince()) : null,
        pastDue && subscription.getPastDueSince() != null
            ? toDisplayOffset(
                subscription.getPastDueSince().plus(BillingPeriods.PAST_DUE_RESTRICT_AFTER))
            : null,
        pastDue && subscription.getPastDueSince() != null
            ? toDisplayOffset(
                subscription.getPastDueSince().plus(BillingPeriods.PAST_DUE_TERMINATE_AFTER))
            : null,
        toDisplayOffset(cancellation.orElse(null)),
        queryRepository.outstandingAmount(officeId),
        retryTarget && !queryRepository.hasInProgressPayment(officeId),
        latest
            .map(
                row ->
                    new SubscriptionCurrentResponse.LatestInvoice(
                        row.id(),
                        row.status(),
                        toDisplayOffset(row.nextRetryAt()),
                        row.lastFailureCategory()))
            .orElse(null),
        paymentMethod,
        new SubscriptionCurrentResponse.Readiness(
            hasValidConsent(
                subscription,
                selection
                    .map(found -> PriceCalculator.monthlyAmount(found.table(), memberCount))
                    .orElse(null)),
            hasBillingKey),
        inReturnPeriod,
        inReturnPeriod
            ? toDisplayOffset(BillingPeriods.returnPeriodEnd(subscription.getEndedAt()))
            : null,
        officeId,
        toDisplayOffset(subscription.getCreatedAt()),
        toDisplayOffset(subscription.getUpdatedAt()),
        subscription.getVersion(),
        subscription.getId());
  }

  private SubscriptionCurrentResponse.StartCustomer toStartCustomer(ApprovedAgreementRow row) {
    String version =
        queryRepository.findPriceById(row.priceScheduleId()).map(p -> p.version()).orElse(null);
    return new SubscriptionCurrentResponse.StartCustomer(
        row.id(), version, toDisplayOffset(row.priceEndsAt()));
  }

  private Instant nextChargeAt(Subscription subscription, boolean cancellationReserved) {
    if (cancellationReserved) {
      return null;
    }
    return switch (subscription.getStatus()) {
      case TRIAL -> subscription.getFirstBillingScheduledAt();
      case ACTIVE, PAST_DUE_GRACE, PAST_DUE_RESTRICTED -> subscription.getCurrentPeriodEnd();
      case PRE_PAYMENT, SUSPENDED, ENDED -> null;
    };
  }

  private Instant currentPeriodEnd(Subscription subscription) {
    return switch (subscription.getStatus()) {
      case TRIAL -> subscription.getTrialEndsAt();
      case ACTIVE, PAST_DUE_GRACE, PAST_DUE_RESTRICTED -> subscription.getCurrentPeriodEnd();
      case PRE_PAYMENT ->
          subscription.getPrePaymentSince() == null
              ? null
              : subscription.getPrePaymentSince().plus(BillingPeriods.PRE_PAYMENT_LIMIT);
      case SUSPENDED ->
          subscription.getSuspendedAt() == null
              ? null
              : subscription.getSuspendedAt().plus(BillingPeriods.RETURN_PERIOD);
      case ENDED -> subscription.getEndedAt();
    };
  }

  private boolean hasValidConsent(Subscription subscription, Long expectedAmount) {
    Optional<SubscriptionPurchaseConsent> latest =
        consentRepository.findFirstBySubscriptionIdOrderByIdDesc(subscription.getId());
    if (latest.isEmpty() || !latest.get().isAgreed()) {
      return false;
    }
    Optional<TermsVersionView> inForce = termsCatalogService.findInForceByCode(PURCHASE_TERMS_CODE);
    Optional<TermsAgreement> agreement =
        termsAgreementRepository.findById(latest.get().getTermsAgreementId());
    if (inForce.isEmpty()
        || agreement.isEmpty()
        || !agreement.get().getTermsVersion().equals(inForce.get().version())) {
      return false;
    }
    return expectedAmount == null || expectedAmount <= latest.get().getAmountLimit();
  }
}
