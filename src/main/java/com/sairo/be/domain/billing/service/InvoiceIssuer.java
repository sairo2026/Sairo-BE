package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.entity.BillingInvoice;
import com.sairo.be.domain.billing.entity.BillingInvoice.IssueCommand;
import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.entity.SubscriptionPurchaseConsent;
import com.sairo.be.domain.billing.repository.BillingInvoiceRepository;
import com.sairo.be.domain.billing.repository.BillingQueryRepository;
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

  public InvoiceIssuer(
      PriceScheduleSelector priceSelector,
      BillingContextService context,
      VatCalculator vatCalculator,
      ConsentChecker consentChecker,
      BillingInvoiceRepository invoiceRepository,
      BillingQueryRepository queryRepository) {
    this.priceSelector = priceSelector;
    this.context = context;
    this.vatCalculator = vatCalculator;
    this.consentChecker = consentChecker;
    this.invoiceRepository = invoiceRepository;
    this.queryRepository = queryRepository;
  }

  public Optional<BillingInvoice> issue(
      Subscription subscription, Instant periodStart, Instant periodEnd, Instant dueAt) {
    Long officeId = subscription.getOfficeId();
    PriceSelection selection = priceSelector.select(officeId, periodStart);
    int memberCount = context.billableMembers(officeId);
    long amount = PriceCalculator.monthlyAmount(selection.table(), memberCount);
    Optional<SubscriptionPurchaseConsent> consent =
        consentChecker.findValid(subscription.getId(), amount);
    if (consent.isEmpty()) {
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
