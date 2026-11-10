package com.sairo.be.domain.billing.service;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.auth.dto.response.ChangedTermsItem;
import com.sairo.be.domain.auth.entity.ConsentPath;
import com.sairo.be.domain.auth.entity.TermsAgreement;
import com.sairo.be.domain.auth.repository.TermsAgreementRepository;
import com.sairo.be.domain.billing.dto.request.SubscriptionPurchaseConsentRequest;
import com.sairo.be.domain.billing.dto.response.PurchaseConsentPreviewResponse;
import com.sairo.be.domain.billing.dto.response.SubscriptionPurchaseConsentResponse;
import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.entity.SubscriptionPlan;
import com.sairo.be.domain.billing.entity.SubscriptionPurchaseConsent;
import com.sairo.be.domain.billing.repository.SubscriptionPurchaseConsentRepository;
import com.sairo.be.domain.billing.service.PurchaseQuoteService.Quote;
import com.sairo.be.domain.legal.service.TermsCatalogService;
import com.sairo.be.domain.legal.service.TermsVersionView;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.AdvisoryLock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class PurchaseConsentService {

  public static final String PURCHASE_TERMS_CODE = "SUBSCRIPTION_PURCHASE";
  static final Duration NOW_BASED_TOLERANCE = Duration.ofMinutes(30);
  static final String CANCELLATION_TERMS =
      "해지를 예약하면 현재 이용 기간이 끝날 때까지 이용할 수 있고 그 뒤 자동결제가 중단된다. 해지 예약은 이용 기간이 끝나기 전에 취소할 수 있다.";
  static final String REFUND_TERMS =
      "해지 예약 기간의 남은 기간은 환불하지 않는다. 즉시 종료, 인증 중단, 서비스 장애, 중복 결제, 오청구는 환불 신청 기준에 따라 환불한다.";

  private final BillingContextService context;
  private final PurchaseQuoteService quoteService;
  private final TermsCatalogService termsCatalogService;
  private final TermsAgreementRepository termsAgreementRepository;
  private final SubscriptionPurchaseConsentRepository consentRepository;
  private final AdvisoryLock advisoryLock;
  private final Clock clock;

  public PurchaseConsentService(
      BillingContextService context,
      PurchaseQuoteService quoteService,
      TermsCatalogService termsCatalogService,
      TermsAgreementRepository termsAgreementRepository,
      SubscriptionPurchaseConsentRepository consentRepository,
      AdvisoryLock advisoryLock,
      Clock clock) {
    this.context = context;
    this.quoteService = quoteService;
    this.termsCatalogService = termsCatalogService;
    this.termsAgreementRepository = termsAgreementRepository;
    this.consentRepository = consentRepository;
    this.advisoryLock = advisoryLock;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PurchaseConsentPreviewResponse preview(StaffPrincipal principal, String planParam) {
    validatePlanParam(planParam);
    Subscription subscription = context.requireSubscription(principal.officeId());
    TermsVersionView terms = requireTerms();
    Quote quote = quoteService.quote(principal.officeId(), subscription);
    return new PurchaseConsentPreviewResponse(
        terms.version(),
        quote.plan().name(),
        quote.memberCount(),
        quote.selection().version(),
        null,
        quote.totalAmountInclVat(),
        quote.vatAmount(),
        quote.amountLimit(),
        toDisplayOffset(quote.periodStart()),
        toDisplayOffset(quote.periodEnd()),
        toDisplayOffset(quote.nextChargeAt()),
        toDisplayOffset(quote.firstBillingScheduledAt()),
        true,
        CANCELLATION_TERMS,
        REFUND_TERMS);
  }

  @Transactional
  public SubscriptionPurchaseConsentResponse record(
      StaffPrincipal principal, SubscriptionPurchaseConsentRequest request) {
    if (request.plan() == SubscriptionPlan.ENTERPRISE) {
      throw BusinessException.withFieldErrors(
          ErrorCode.INVALID_REQUEST,
          List.of(
              new FieldErrorItem(
                  "plan", FieldErrorCode.NOT_ALLOWED, "기업 요금제는 구독 구매 동의로 처리할 수 없습니다.")));
    }
    Long officeId = principal.officeId();
    Subscription subscription = context.requireSubscription(officeId);
    advisoryLock.lockForTransaction("billing:subscription:" + subscription.getId());

    TermsVersionView terms = requireTerms();
    if (!terms.version().equals(request.termsVersion())) {
      throw BusinessException.termsVersionChanged(
          List.of(
              new ChangedTermsItem(
                  terms.termsCode(),
                  request.termsVersion(),
                  terms.version(),
                  terms.changeSummary(),
                  terms.documentUrl())));
    }
    Quote quote = quoteService.quote(officeId, subscription);
    verifyMatchesQuote(request, quote);

    Instant now = clock.instant();
    TermsAgreement agreement =
        termsAgreementRepository.save(
            TermsAgreement.recordForOffice(
                principal.userId(),
                officeId,
                terms.termsCode(),
                terms.version(),
                true,
                ConsentPath.PURCHASE_CONSENT,
                now));
    consentRepository.save(
        SubscriptionPurchaseConsent.record(
            subscription.getId(),
            principal.userId(),
            agreement.getId(),
            quote.plan(),
            quote.memberCount(),
            quote.totalAmountInclVat(),
            quote.vatAmount(),
            quote.amountLimit(),
            instantOf(request.periodStart()),
            instantOf(request.periodEnd()),
            instantOf(request.nextChargeAt()),
            true,
            now));
    subscription.markEventRecorded(now);
    return new SubscriptionPurchaseConsentResponse(
        terms.version(),
        quote.totalAmountInclVat(),
        quote.vatAmount(),
        quote.amountLimit(),
        toDisplayOffset(now));
  }

  private void verifyMatchesQuote(SubscriptionPurchaseConsentRequest request, Quote quote) {
    boolean matches =
        request.plan() == quote.plan()
            && request.memberCount() == quote.memberCount()
            && request.totalAmountInclVat() == quote.totalAmountInclVat()
            && request.amountLimit() == quote.amountLimit()
            && (request.priceVersion() == null
                || request.priceVersion().equals(quote.selection().version()))
            && periodMatches(request, quote);
    if (!matches) {
      throw new BusinessException(ErrorCode.CONSENT_TERMS_MISMATCH);
    }
  }

  private boolean periodMatches(SubscriptionPurchaseConsentRequest request, Quote quote) {
    Instant start = instantOf(request.periodStart());
    Instant end = instantOf(request.periodEnd());
    Instant next = instantOf(request.nextChargeAt());
    if (quote.anchored()) {
      return sameSecond(start, quote.periodStart())
          && sameSecond(end, quote.periodEnd())
          && sameSecond(next, quote.nextChargeAt());
    }
    Duration drift = Duration.between(start, quote.periodStart()).abs();
    return drift.compareTo(NOW_BASED_TOLERANCE) <= 0
        && sameSecond(end, BillingPeriods.boundary(start, 1))
        && sameSecond(next, end);
  }

  private static boolean sameSecond(Instant first, Instant second) {
    return first.truncatedTo(ChronoUnit.SECONDS).equals(second.truncatedTo(ChronoUnit.SECONDS));
  }

  private static Instant instantOf(OffsetDateTime value) {
    return value.toInstant();
  }

  private TermsVersionView requireTerms() {
    return termsCatalogService
        .findInForceByCode(PURCHASE_TERMS_CODE)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  private void validatePlanParam(String planParam) {
    if (planParam == null || planParam.isBlank()) {
      return;
    }
    if (!"SOLO".equals(planParam) && !"TEAM".equals(planParam)) {
      throw BusinessException.withFieldErrors(
          ErrorCode.INVALID_REQUEST,
          List.of(
              new FieldErrorItem(
                  "plan", FieldErrorCode.NOT_ALLOWED, "plan은 SOLO 또는 TEAM이어야 합니다.")));
    }
  }
}
