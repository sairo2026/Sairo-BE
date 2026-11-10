package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.auth.repository.TermsAgreementRepository;
import com.sairo.be.domain.billing.entity.SubscriptionPurchaseConsent;
import com.sairo.be.domain.billing.repository.SubscriptionPurchaseConsentRepository;
import com.sairo.be.domain.legal.service.TermsCatalogService;
import com.sairo.be.domain.legal.service.TermsVersionView;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class ConsentChecker {

  private final SubscriptionPurchaseConsentRepository consentRepository;
  private final TermsAgreementRepository termsAgreementRepository;
  private final TermsCatalogService termsCatalogService;

  public ConsentChecker(
      SubscriptionPurchaseConsentRepository consentRepository,
      TermsAgreementRepository termsAgreementRepository,
      TermsCatalogService termsCatalogService) {
    this.consentRepository = consentRepository;
    this.termsAgreementRepository = termsAgreementRepository;
    this.termsCatalogService = termsCatalogService;
  }

  public Optional<SubscriptionPurchaseConsent> latest(Long subscriptionId) {
    return consentRepository.findFirstBySubscriptionIdOrderByIdDesc(subscriptionId);
  }

  public Optional<SubscriptionPurchaseConsent> findValid(Long subscriptionId, long amount) {
    return latest(subscriptionId)
        .filter(SubscriptionPurchaseConsent::isAgreed)
        .filter(consent -> amount <= consent.getAmountLimit())
        .filter(this::matchesCurrentTerms);
  }

  private boolean matchesCurrentTerms(SubscriptionPurchaseConsent consent) {
    Optional<TermsVersionView> inForce =
        termsCatalogService.findInForceByCode(PurchaseConsentService.PURCHASE_TERMS_CODE);
    return inForce.isPresent()
        && termsAgreementRepository
            .findById(consent.getTermsAgreementId())
            .map(agreement -> agreement.getTermsVersion().equals(inForce.get().version()))
            .orElse(false);
  }

  public Optional<String> consentVersion(SubscriptionPurchaseConsent consent) {
    return termsAgreementRepository
        .findById(consent.getTermsAgreementId())
        .map(agreement -> agreement.getTermsVersion());
  }
}
