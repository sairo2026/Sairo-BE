package com.sairo.be.domain.billing.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

@IntegrationTest
class PublicPricingIntegrationTest extends BillingIntegrationTestBase {

  private static final String URL = "/api/public/pricing";

  private Instant daysAgo(long days) {
    return Instant.now().minus(days, ChronoUnit.DAYS);
  }

  @Test
  void 시행_중인_표준_요금표를_로그인_없이_조회하고_캐시_헤더와_인원별_예시를_준다() throws Exception {
    fixtures.standardPrice("2026-12-STD", daysAgo(1));
    mockMvc
        .perform(get(URL))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", containsString("max-age=300")))
        .andExpect(header().string("Cache-Control", containsString("public")))
        .andExpect(jsonPath("$.priceVersion").value("2026-12-STD"))
        .andExpect(jsonPath("$.currency").value("KRW"))
        .andExpect(jsonPath("$.vatIncluded").value(true))
        .andExpect(jsonPath("$.billingCycle").value("MONTHLY_PREPAID"))
        .andExpect(jsonPath("$.autoRenew").value(true))
        .andExpect(jsonPath("$.provisionalPrice").value(true))
        .andExpect(jsonPath("$.plans.length()").value(3))
        .andExpect(jsonPath("$.plans[0].planCode").value("SOLO"))
        .andExpect(jsonPath("$.plans[0].baseAmount").value(39_900))
        .andExpect(jsonPath("$.plans[0].perSeatAmount").value(nullValue()))
        .andExpect(jsonPath("$.plans[1].minMembers").value(2))
        .andExpect(jsonPath("$.plans[1].maxMembers").value(4))
        .andExpect(jsonPath("$.plans[1].perSeatAmount").value(10_000))
        .andExpect(jsonPath("$.plans[2].minMembers").value(5))
        .andExpect(jsonPath("$.plans[2].maxMembers").value(nullValue()))
        .andExpect(jsonPath("$.plans[2].baseAmount").value(69_900))
        .andExpect(jsonPath("$.plans[2].includedMembers").value(4))
        .andExpect(jsonPath("$.plans[2].perSeatAmount").value(8_000))
        .andExpect(jsonPath("$.examples.length()").value(10))
        .andExpect(jsonPath("$.examples[0].monthlyAmount").value(39_900))
        .andExpect(jsonPath("$.examples[1].monthlyAmount").value(49_900))
        .andExpect(jsonPath("$.examples[3].monthlyAmount").value(69_900))
        .andExpect(jsonPath("$.examples[4].monthlyAmount").value(77_900))
        .andExpect(jsonPath("$.examples[9].monthlyAmount").value(117_900))
        .andExpect(jsonPath("$.enterprise.quotedByInquiry").value(true))
        .andExpect(jsonPath("$.enterprise.autoChargeBeforeApproval").value(false))
        .andExpect(jsonPath("$.generalTrial").value(nullValue()))
        .andExpect(jsonPath("$.links.termsUrl").value("/legal/terms/TOS/2026-09-01"))
        .andExpect(jsonPath("$.links.refundUrl").value("/legal/refund-policy"))
        .andExpect(jsonPath("$.links.privacyUrl").value("/legal/terms/PRIVACY/2026-09-01"));
  }

  @Test
  void 시행_중인_요금표가_없으면_404_PRICE_SCHEDULE_NOT_PUBLISHED이고_초안_검토_예약은_노출하지_않는다() throws Exception {
    fixtures.price("DRAFT-V", "STANDARD", daysAgo(1), "DRAFT", 39_900);
    fixtures.price("REVIEW-V", "STANDARD", daysAgo(1), "REVIEW", 39_900);
    fixtures.price("SCHEDULED-V", "STANDARD", daysAgo(1), "SCHEDULED", 39_900);
    assertError(mockMvc.perform(get(URL)), ErrorCode.PRICE_SCHEDULE_NOT_PUBLISHED);
  }

  @Test
  void 효력_시각이_아직_오지_않은_요금표는_노출하지_않고_가장_늦은_효력_버전을_고른다() throws Exception {
    fixtures.standardPrice("OLD-V", daysAgo(30));
    fixtures.standardPrice("CURRENT-V", daysAgo(2));
    fixtures.standardPrice("FUTURE-V", Instant.now().plus(10, ChronoUnit.DAYS));
    mockMvc
        .perform(get(URL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.priceVersion").value("CURRENT-V"));
  }

  @Test
  void 시작_고객_전용_요금표는_공개_요금으로_노출하지_않는다() throws Exception {
    fixtures.price("START-V", "START_CUSTOMER", daysAgo(1), "ACTIVE", 29_900);
    assertError(mockMvc.perform(get(URL)), ErrorCode.PRICE_SCHEDULE_NOT_PUBLISHED);
    fixtures.standardPrice("STD-V", daysAgo(1));
    mockMvc
        .perform(get(URL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.priceVersion").value("STD-V"))
        .andExpect(jsonPath("$.plans[0].baseAmount").value(39_900));
  }

  @Test
  void 활성_일반_체험_이벤트가_있으면_무료_방식을_준다() throws Exception {
    fixtures.standardPrice("STD-V", daysAgo(1));
    fixtures.trialEvent(
        "GENERAL", "DURATION", daysAgo(5), Instant.now().plus(30, ChronoUnit.DAYS), null, "ACTIVE");
    mockMvc
        .perform(get(URL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.generalTrial.freeEndMode").value("DURATION"))
        .andExpect(jsonPath("$.generalTrial.durationDays").value(60))
        .andExpect(jsonPath("$.generalTrial.fixedFreeEndsAt").value(nullValue()))
        .andExpect(jsonPath("$.generalTrial.applicantWindowEndsAt").exists());
  }

  @Test
  void 고정_종료_방식_이벤트는_고정_무료_종료_시각을_주고_시작_고객_이벤트는_노출하지_않는다() throws Exception {
    fixtures.standardPrice("STD-V", daysAgo(1));
    fixtures.trialEvent(
        "START_CUSTOMER",
        "FIXED_END",
        daysAgo(5),
        Instant.now().plus(30, ChronoUnit.DAYS),
        Instant.now().plus(90, ChronoUnit.DAYS),
        "ACTIVE");
    mockMvc
        .perform(get(URL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.generalTrial").value(nullValue()));
    Instant fixedEnd = Instant.now().plus(60, ChronoUnit.DAYS);
    fixtures.trialEvent(
        "GENERAL",
        "FIXED_END",
        daysAgo(5),
        Instant.now().plus(30, ChronoUnit.DAYS),
        fixedEnd,
        "ACTIVE");
    mockMvc
        .perform(get(URL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.generalTrial.freeEndMode").value("FIXED_END"))
        .andExpect(jsonPath("$.generalTrial.durationDays").value(nullValue()))
        .andExpect(jsonPath("$.generalTrial.fixedFreeEndsAt").exists());
  }

  @Test
  void 약관_환불정책_개인정보처리방침이_게시돼_있으면_해당_주소와_환불정책_버전을_준다() throws Exception {
    fixtures.standardPrice("STD-V", daysAgo(1));
    fixtures.insertTerms("REFUND_POLICY", "2026-11", "환불정책", "NOTICE", false, "PURCHASE");
    mockMvc
        .perform(get(URL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.refundPolicyVersion").value("2026-11"))
        .andExpect(jsonPath("$.links.termsUrl").value("/legal/terms/TOS/2026-09-01"))
        .andExpect(jsonPath("$.links.refundUrl").value("/legal/terms/REFUND_POLICY/2026-11"))
        .andExpect(jsonPath("$.links.privacyUrl").value("/legal/terms/PRIVACY/2026-09-01"));
  }

  @Test
  void IP당_60초_60회를_넘기면_429_RATE_LIMITED이고_retryAfterSeconds를_준다() throws Exception {
    fixtures.standardPrice("STD-V", daysAgo(1));
    for (int i = 0; i < 60; i++) {
      mockMvc.perform(get(URL)).andExpect(status().isOk());
    }
    assertError(mockMvc.perform(get(URL)), ErrorCode.RATE_LIMITED)
        .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
  }
}
