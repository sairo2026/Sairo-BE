package com.sairo.be.domain.billing.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.BillingFixtures;
import com.sairo.be.support.fixture.TestData;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class SubscriptionCurrentIntegrationTest extends BillingIntegrationTestBase {

  private static final String URL = "/api/subscriptions/current";

  @BeforeEach
  void seedPrice() {
    fixtures.standardPrice("2026-12-STD", daysFromNow(-1));
    fixtures.purchaseTerms(BillingFixtures.PURCHASE_TERMS_VERSION);
  }

  private static Instant daysFromNow(long days) {
    return Instant.now().plus(days, ChronoUnit.DAYS);
  }

  private ResultActions current(TestData.Staff owner) throws Exception {
    return mockMvc.perform(getRequest(URL).apply(cookieOf(owner)));
  }

  private long trialEvent() {
    return fixtures.trialEvent(
        "GENERAL", "DURATION", daysFromNow(-5), daysFromNow(30), null, "ACTIVE");
  }

  @Test
  void 무료체험_중이면_체험_종료_시각과_첫_청구_예정_시각과_다음_청구를_준다() throws Exception {
    TestData.Staff owner = owner();
    Instant ends = daysFromNow(40);
    fixtures
        .subscription(owner.office().getId())
        .status("TRIAL")
        .trial(trialEvent(), daysFromNow(-20), ends)
        .insert();
    current(owner)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("TRIAL"))
        .andExpect(jsonPath("$.plan").value("SOLO"))
        .andExpect(jsonPath("$.memberCount").value(1))
        .andExpect(jsonPath("$.priceVersion").value("2026-12-STD"))
        .andExpect(jsonPath("$.startCustomer").value(nullValue()))
        .andExpect(jsonPath("$.pendingPriceChangeNoticeCount").value(0))
        .andExpect(jsonPath("$.trialEndsAt").exists())
        .andExpect(jsonPath("$.firstBillingScheduledAt").exists())
        .andExpect(jsonPath("$.nextChargeAt").exists())
        .andExpect(jsonPath("$.nextChargeAmount").value(39_900))
        .andExpect(jsonPath("$.prePaymentSince").value(nullValue()))
        .andExpect(jsonPath("$.pastDueSince").value(nullValue()))
        .andExpect(jsonPath("$.cancellationEffectiveAt").value(nullValue()))
        .andExpect(jsonPath("$.outstandingAmount").value(0))
        .andExpect(jsonPath("$.canRetryPayment").value(false))
        .andExpect(jsonPath("$.latestInvoice").value(nullValue()))
        .andExpect(jsonPath("$.paymentMethod").value(nullValue()))
        .andExpect(jsonPath("$.readiness.hasValidConsent").value(false))
        .andExpect(jsonPath("$.readiness.hasBillingKey").value(false))
        .andExpect(jsonPath("$.isInReturnPeriod").value(false))
        .andExpect(jsonPath("$.returnPeriodEndsAt").value(nullValue()))
        .andExpect(jsonPath("$.officeId").value(owner.office().getId()))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.subscriptionId").isNumber());
  }

  @Test
  void 결제_전_상태는_결제_전_시작_시각을_주고_다음_청구가_없다() throws Exception {
    TestData.Staff owner = owner();
    fixtures
        .subscription(owner.office().getId())
        .status("PRE_PAYMENT")
        .prePaymentSince(daysFromNow(-3))
        .insert();
    current(owner)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PRE_PAYMENT"))
        .andExpect(jsonPath("$.prePaymentSince").exists())
        .andExpect(jsonPath("$.nextChargeAt").value(nullValue()))
        .andExpect(jsonPath("$.nextChargeAmount").value(nullValue()))
        .andExpect(jsonPath("$.trialEndsAt").value(nullValue()))
        .andExpect(jsonPath("$.currentPeriodEnd").exists());
  }

  @Test
  void 구독중이면_다음_청구_시각과_금액_등록_카드_결제_준비_상태와_최근_청구서를_준다() throws Exception {
    TestData.Staff owner = owner();
    Instant periodStart = daysFromNow(-10);
    Instant periodEnd = daysFromNow(20);
    long subscriptionId =
        fixtures
            .subscription(owner.office().getId())
            .status("ACTIVE")
            .period(periodStart, periodEnd)
            .insert();
    long consent =
        fixtures.consent(
            subscriptionId, owner.user().getId(), owner.office().getId(), 39_900, 3_627, 39_900);
    fixtures.invoice(owner.office().getId(), subscriptionId, consent).insert();
    fixtures.billingCustomer(owner.office().getId(), "customer-key-1", true);
    current(owner)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.nextChargeAt").exists())
        .andExpect(jsonPath("$.nextChargeAmount").value(39_900))
        .andExpect(jsonPath("$.paymentMethod.cardIssuer").value("국민카드"))
        .andExpect(jsonPath("$.paymentMethod.cardLast4").value("1234"))
        .andExpect(jsonPath("$.paymentMethod.registeredAt").exists())
        .andExpect(jsonPath("$.readiness.hasValidConsent").value(true))
        .andExpect(jsonPath("$.readiness.hasBillingKey").value(true))
        .andExpect(jsonPath("$.latestInvoice.status").value("PAID"))
        .andExpect(jsonPath("$.latestInvoice.nextRetryAt").value(nullValue()))
        .andExpect(jsonPath("$.latestInvoice.lastFailureCategory").value(nullValue()))
        .andExpect(jsonPath("$.outstandingAmount").value(0))
        .andExpect(jsonPath("$.canRetryPayment").value(false));
  }

  @Test
  void 동의_한도가_현재_금액보다_작으면_결제_준비_상태의_동의가_유효하지_않다() throws Exception {
    TestData.Staff owner = owner();
    long subscriptionId =
        fixtures
            .subscription(owner.office().getId())
            .status("ACTIVE")
            .period(daysFromNow(-10), daysFromNow(20))
            .insert();
    fixtures.consent(
        subscriptionId, owner.user().getId(), owner.office().getId(), 29_900, 2_718, 29_900);
    current(owner)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.readiness.hasValidConsent").value(false));
  }

  @Test
  void 해지_예약이_있으면_해지_발효_시각을_주고_다음_청구가_없다() throws Exception {
    TestData.Staff owner = owner();
    long subscriptionId =
        fixtures
            .subscription(owner.office().getId())
            .status("ACTIVE")
            .period(daysFromNow(-10), daysFromNow(20))
            .insert();
    jdbcTemplate.update(
        "INSERT INTO subscription_cancellation (subscription_id, effective_at,"
            + " cancelled_by_membership_id) VALUES (?, ?, ?)",
        subscriptionId,
        java.sql.Timestamp.from(daysFromNow(20)),
        owner.membership().getId());
    current(owner)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cancellationEffectiveAt").exists())
        .andExpect(jsonPath("$.nextChargeAt").value(nullValue()))
        .andExpect(jsonPath("$.nextChargeAmount").value(nullValue()));
  }

  @Test
  void 미납_상태는_미납_시작_제한_종료_시각과_연체_청구액과_재결제_가능_여부를_준다() throws Exception {
    TestData.Staff owner = owner();
    Instant pastDue = daysFromNow(-9);
    long subscriptionId =
        fixtures
            .subscription(owner.office().getId())
            .status("PAST_DUE_RESTRICTED")
            .period(daysFromNow(-39), pastDue)
            .pastDueSince(pastDue)
            .insert();
    long consent =
        fixtures.consent(
            subscriptionId, owner.user().getId(), owner.office().getId(), 39_900, 3_627, 39_900);
    fixtures
        .invoice(owner.office().getId(), subscriptionId, consent)
        .status("FAILED")
        .amount(39_900)
        .insert();
    current(owner)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PAST_DUE_RESTRICTED"))
        .andExpect(jsonPath("$.pastDueSince").exists())
        .andExpect(jsonPath("$.restrictedAt").exists())
        .andExpect(jsonPath("$.terminationAt").exists())
        .andExpect(jsonPath("$.outstandingAmount").value(39_900))
        .andExpect(jsonPath("$.canRetryPayment").value(true))
        .andExpect(jsonPath("$.latestInvoice.status").value("FAILED"));
  }

  @Test
  void 처리_중인_결제가_있으면_재결제_버튼을_쓸_수_없다() throws Exception {
    TestData.Staff owner = owner();
    long subscriptionId =
        fixtures
            .subscription(owner.office().getId())
            .status("PAST_DUE_GRACE")
            .period(daysFromNow(-31), daysFromNow(-1))
            .pastDueSince(daysFromNow(-1))
            .insert();
    long consent =
        fixtures.consent(
            subscriptionId, owner.user().getId(), owner.office().getId(), 39_900, 3_627, 39_900);
    fixtures.invoice(owner.office().getId(), subscriptionId, consent).status("VERIFYING").insert();
    current(owner)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canRetryPayment").value(false))
        .andExpect(jsonPath("$.latestInvoice.status").value("VERIFYING"));
  }

  @Test
  void 종료_후_반환기간_중이면_반환기간_종료_시각과_재결제_가능_여부를_준다() throws Exception {
    TestData.Staff owner = owner();
    fixtures.subscription(owner.office().getId()).status("ENDED").endedAt(daysFromNow(-5)).insert();
    current(owner)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ENDED"))
        .andExpect(jsonPath("$.isInReturnPeriod").value(true))
        .andExpect(jsonPath("$.returnPeriodEndsAt").exists())
        .andExpect(jsonPath("$.canRetryPayment").value(true))
        .andExpect(jsonPath("$.nextChargeAt").value(nullValue()));
  }

  @Test
  void 반환기간이_지난_종료_구독은_반환기간이_아니다() throws Exception {
    TestData.Staff owner = owner();
    fixtures
        .subscription(owner.office().getId())
        .status("ENDED")
        .endedAt(daysFromNow(-31))
        .insert();
    current(owner)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isInReturnPeriod").value(false))
        .andExpect(jsonPath("$.returnPeriodEndsAt").value(nullValue()))
        .andExpect(jsonPath("$.canRetryPayment").value(false));
  }

  @Test
  void 승인된_시작_고객_합의_요약과_응답_대기_가격_변경_안내_수를_준다() throws Exception {
    TestData.Staff owner = owner();
    long startPrice =
        fixtures.price("2026-12-START", "START_CUSTOMER", daysFromNow(-1), "ACTIVE", 29_900);
    long event =
        fixtures.trialEvent(
            "START_CUSTOMER",
            "FIXED_END",
            daysFromNow(-5),
            daysFromNow(30),
            daysFromNow(200),
            "ACTIVE");
    fixtures
        .subscription(owner.office().getId())
        .status("TRIAL")
        .trial(event, daysFromNow(-3), daysFromNow(200))
        .insert();
    long agreementId =
        fixtures.startCustomerAgreement(
            owner.office().getId(),
            event,
            startPrice,
            daysFromNow(-1),
            daysFromNow(365),
            "APPROVED");
    current(owner)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.priceVersion").value("2026-12-START"))
        .andExpect(jsonPath("$.startCustomer.agreementId").value(agreementId))
        .andExpect(jsonPath("$.startCustomer.priceVersion").value("2026-12-START"))
        .andExpect(jsonPath("$.startCustomer.priceEndsAt").exists());
  }

  @Test
  void 구독이_아직_없으면_404_RESOURCE_NOT_FOUND이다() throws Exception {
    assertError(
        mockMvc.perform(getRequest(URL).apply(cookieOf(owner()))), ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 다른_사무소의_구독은_보이지_않고_자기_사무소_구독만_준다() throws Exception {
    TestData.Staff other = owner();
    fixtures.subscription(other.office().getId()).status("PRE_PAYMENT").insert();
    TestData.Staff mine = owner();
    fixtures.subscription(mine.office().getId()).status("ENDED").endedAt(daysFromNow(-1)).insert();
    current(mine)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ENDED"))
        .andExpect(jsonPath("$.officeId").value(mine.office().getId()));
  }

  @Test
  void 대표만_조회할_수_있다() throws Exception {
    assertOwnerOnly(getRequest(URL));
  }
}
