package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.auth.entity.ConsentPath;
import com.sairo.be.domain.auth.entity.TermsAgreement;
import com.sairo.be.domain.auth.repository.TermsAgreementRepository;
import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.entity.SubscriptionPurchaseConsent;
import com.sairo.be.domain.billing.repository.SubscriptionPurchaseConsentRepository;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class ConsentWithdrawals {

  private final ConsentChecker consentChecker;
  private final TermsAgreementRepository termsAgreementRepository;
  private final SubscriptionPurchaseConsentRepository consentRepository;

  public ConsentWithdrawals(
      ConsentChecker consentChecker,
      TermsAgreementRepository termsAgreementRepository,
      SubscriptionPurchaseConsentRepository consentRepository) {
    this.consentChecker = consentChecker;
    this.termsAgreementRepository = termsAgreementRepository;
    this.consentRepository = consentRepository;
  }

  public void record(Subscription subscription, Instant now) {
    consentChecker
        .latest(subscription.getId())
        .filter(SubscriptionPurchaseConsent::isAgreed)
        .ifPresent(
            latest -> {
              String version =
                  consentChecker
                      .consentVersion(latest)
                      .orElseThrow(() -> new IllegalStateException("동의 이벤트가 없습니다."));
              TermsAgreement agreement =
                  termsAgreementRepository.save(
                      TermsAgreement.recordForOffice(
                          latest.getUserId(),
                          subscription.getOfficeId(),
                          PurchaseConsentService.PURCHASE_TERMS_CODE,
                          version,
                          false,
                          ConsentPath.PURCHASE_CONSENT,
                          now));
              consentRepository.save(
                  SubscriptionPurchaseConsent.record(
                      subscription.getId(),
                      latest.getUserId(),
                      agreement.getId(),
                      latest.getPlan(),
                      latest.getMemberCount(),
                      latest.getTotalAmountInclVat(),
                      latest.getVatAmount(),
                      latest.getAmountLimit(),
                      latest.getPeriodStart(),
                      latest.getPeriodEnd(),
                      latest.getNextChargeAt(),
                      false,
                      now));
            });
  }
}
