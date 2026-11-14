package com.sairo.be.domain.billing.controller;

import com.sairo.be.global.support.TextCipher;
import com.sairo.be.support.fixture.BillingFixtures;
import com.sairo.be.support.fixture.BillingFixtures.SubscriptionSeed;
import com.sairo.be.support.fixture.TestData;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

abstract class PaymentTestBase extends BillingIntegrationTestBase {

  protected static final String BILLING_KEY = "plain-billing-key-1";

  @Autowired
  @Qualifier("billingKeyCipher") protected TextCipher billingKeyCipher;

  @BeforeEach
  void seedPriceAndTerms() {
    fixtures.standardPrice("2026-12-STD", Instant.now().minus(60, ChronoUnit.DAYS));
    fixtures.purchaseTerms(BillingFixtures.PURCHASE_TERMS_VERSION);
  }

  protected static Instant daysFromNow(long days) {
    return Instant.now().plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
  }

  protected Ready ready(UnaryOperator<SubscriptionSeed> seed, boolean consent, boolean key) {
    TestData.Staff owner = owner();
    long subscriptionId = seed.apply(fixtures.subscription(owner.office().getId())).insert();
    long consentId = 0;
    if (consent) {
      consentId =
          fixtures.consent(
              subscriptionId, owner.user().getId(), owner.office().getId(), 39_900, 3_627, 39_900);
    }
    if (key) {
      fixtures.billingCustomerWithKey(
          owner.office().getId(),
          "customer-key-" + owner.office().getId(),
          billingKeyCipher.encrypt(BILLING_KEY));
    }
    return new Ready(owner, subscriptionId, consentId);
  }

  protected record Ready(TestData.Staff owner, long subscriptionId, long consentId) {}
}
