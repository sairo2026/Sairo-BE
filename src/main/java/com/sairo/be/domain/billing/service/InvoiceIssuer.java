package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.entity.BillingInvoice;
import com.sairo.be.domain.billing.entity.BillingInvoice.IssueCommand;
import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.entity.SubscriptionPurchaseConsent;
import com.sairo.be.domain.billing.repository.BillingInvoiceRepository;
import com.sairo.be.domain.billing.repository.BillingQueryRepository;
import com.sairo.be.domain.billing.repository.PromotionRepository;
import com.sairo.be.domain.billing.service.PriceScheduleSelector.PriceSelection;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class InvoiceIssuer {

  private final PriceScheduleSelector priceSelector;
  private final BillingContextService context;
  private final VatCalculator vatCalculator;
  private final ConsentChecker consentChecker;
  private final BillingInvoiceRepository invoiceRepository;
  private final BillingQueryRepository queryRepository;
  private final PromotionRepository promotionRepository;

  public InvoiceIssuer(
      PriceScheduleSelector priceSelector,
      BillingContextService context,
      VatCalculator vatCalculator,
      ConsentChecker consentChecker,
      BillingInvoiceRepository invoiceRepository,
      BillingQueryRepository queryRepository,
      PromotionRepository promotionRepository) {
    this.priceSelector = priceSelector;
    this.context = context;
    this.vatCalculator = vatCalculator;
    this.consentChecker = consentChecker;
    this.invoiceRepository = invoiceRepository;
    this.queryRepository = queryRepository;
    this.promotionRepository = promotionRepository;
  }

  public Optional<BillingInvoice> issue(
      Subscription subscription, Instant periodStart, Instant periodEnd, Instant dueAt) {
    if (promotionRepository.requiresReconciliation(subscription.getId())) {
      return Optional.empty();
    }
    Long officeId = subscription.getOfficeId();
    PriceSelection selection = priceSelector.select(officeId, periodStart);
    int memberCount = context.billableMembers(officeId);
    long amount = PriceCalculator.monthlyAmount(selection.table(), memberCount);
    // 0원 혜택은 별도 내부 거래로 처리해야 한다. 미청구 청구서를 만들고 PG에 보내지 않는다.
    BillingChargeService.requireSupportedPgAmount(amount);
    Optional<SubscriptionPurchaseConsent> consent =
        consentChecker.findValid(subscription.getId(), amount);
    if (consent.isEmpty()) {
      return Optional.empty();
    }
    if (promotionRepository
        .conversionBoundary(subscription.getId())
        .filter(boundary -> !consent.get().getRecordedAt().isAfter(boundary))
        .isPresent()) {
      return Optional.empty();
    }
    long vat = vatCalculator.vatAmount(amount);
    BillingInvoice invoice =
        BillingInvoice.issue(
            new IssueCommand(
                officeId,
                subscription.getId(),
                periodStart,
                periodEnd,
                amount,
                memberCount,
                selection.version(),
                consentChecker.consentVersion(consent.get()).orElse(null),
                consent.get().getAmountLimit(),
                consent.get().getRecordedAt(),
                consent.get().getId(),
                dueAt,
                amount - vat,
                vat,
                vatCalculator.rateBp(),
                queryRepository.findPublishedSupplierVersion().orElse(null)));
    return Optional.of(invoiceRepository.saveAndFlush(invoice));
  }
}
