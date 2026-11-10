package com.sairo.be.domain.billing.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.TestData;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

@IntegrationTest
class BillingPlanIntegrationTest extends BillingIntegrationTestBase {

  private static final String URL = "/api/billing/plans";

  private Instant daysAgo(long days) {
    return Instant.now().minus(days, ChronoUnit.DAYS);
  }

  @Test
  void 현재_과금_인원_기준_예상_금액과_가격_버전과_구간_요금표를_준다() throws Exception {
    fixtures.standardPrice("2026-12-STD", daysAgo(1));
    TestData.Staff owner = owner();
    memberIn(owner, OfficeMembershipRole.AGENT);
    memberIn(owner, OfficeMembershipRole.AGENT);
    memberIn(owner, OfficeMembershipRole.DEPUTY);
    mockMvc
        .perform(getRequest(URL).apply(cookieOf(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.priceVersion").value("2026-12-STD"))
        .andExpect(jsonPath("$.priceKind").value("STANDARD"))
        .andExpect(jsonPath("$.vatIncluded").value(true))
        .andExpect(jsonPath("$.memberCount").value(4))
        .andExpect(jsonPath("$.plans.length()").value(3))
        .andExpect(jsonPath("$.plans[0].planCode").value("SOLO"))
        .andExpect(jsonPath("$.plans[0].estimatedAmount").value(39_900))
        .andExpect(jsonPath("$.plans[1].planCode").value("TEAM"))
        .andExpect(jsonPath("$.plans[1].basePrice").value(39_900))
        .andExpect(jsonPath("$.plans[1].perSeatPrice").value(10_000))
        .andExpect(jsonPath("$.plans[1].estimatedAmount").value(69_900))
        .andExpect(jsonPath("$.plans[1].tiers.length()").value(2))
        .andExpect(jsonPath("$.plans[1].tiers[0].minMembers").value(2))
        .andExpect(jsonPath("$.plans[1].tiers[0].maxMembers").value(4))
        .andExpect(jsonPath("$.plans[1].tiers[1].minMembers").value(5))
        .andExpect(jsonPath("$.plans[1].tiers[1].maxMembers").value(nullValue()))
        .andExpect(jsonPath("$.plans[1].tiers[1].basePrice").value(69_900))
        .andExpect(jsonPath("$.plans[1].tiers[1].includedMembers").value(4))
        .andExpect(jsonPath("$.plans[2].planCode").value("ENTERPRISE"))
        .andExpect(jsonPath("$.plans[2].basePrice").value(nullValue()))
        .andExpect(jsonPath("$.plans[2].tiers.length()").value(0))
        .andExpect(jsonPath("$.plans[2].estimatedAmount").value(nullValue()));
  }

  @Test
  void 과금_인원은_정상_접근_중인_승인_구성원만_센다() throws Exception {
    fixtures.standardPrice("2026-12-STD", daysAgo(1));
    TestData.Staff owner = owner();
    TestData.Staff pending = memberIn(owner, OfficeMembershipRole.AGENT);
    TestData.Staff suspended = memberIn(owner, OfficeMembershipRole.AGENT);
    TestData.Staff withdrawn = memberIn(owner, OfficeMembershipRole.AGENT);
    memberIn(owner, OfficeMembershipRole.AGENT);
    jdbcTemplate.update(
        "UPDATE office_membership SET status = 'PENDING' WHERE id = ?",
        pending.membership().getId());
    jdbcTemplate.update(
        "UPDATE office_membership SET status = 'SUSPENDED', suspended_at = now(),"
            + " suspension_reason = '정지' WHERE id = ?",
        suspended.membership().getId());
    jdbcTemplate.update(
        "UPDATE app_user SET account_status = 'WITHDRAWN', withdrawn_at = now() WHERE id = ?",
        withdrawn.user().getId());
    mockMvc
        .perform(getRequest(URL).apply(cookieOf(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.memberCount").value(2))
        .andExpect(jsonPath("$.plans[1].estimatedAmount").value(49_900));
  }

  @Test
  void 승인된_시작_고객_합의가_있으면_합의_가격_버전과_종류를_준다() throws Exception {
    fixtures.standardPrice("2026-12-STD", daysAgo(1));
    long startPrice =
        fixtures.price("2026-12-START", "START_CUSTOMER", daysAgo(1), "ACTIVE", 29_900);
    long event =
        fixtures.trialEvent(
            "START_CUSTOMER",
            "FIXED_END",
            daysAgo(5),
            Instant.now().plus(30, ChronoUnit.DAYS),
            Instant.now().plus(200, ChronoUnit.DAYS),
            "ACTIVE");
    TestData.Staff owner = owner();
    fixtures.startCustomerAgreement(
        owner.office().getId(),
        event,
        startPrice,
        daysAgo(1),
        Instant.now().plus(365, ChronoUnit.DAYS),
        "APPROVED");
    mockMvc
        .perform(getRequest(URL).apply(cookieOf(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.priceVersion").value("2026-12-START"))
        .andExpect(jsonPath("$.priceKind").value("START_CUSTOMER"))
        .andExpect(jsonPath("$.plans[0].estimatedAmount").value(29_900));
  }

  @Test
  void 합의가_등록만_됐거나_취소됐거나_보장_기간이_지나면_표준_가격을_준다() throws Exception {
    fixtures.standardPrice("2026-12-STD", daysAgo(1));
    long startPrice =
        fixtures.price("2026-12-START", "START_CUSTOMER", daysAgo(1), "ACTIVE", 29_900);
    long event =
        fixtures.trialEvent(
            "START_CUSTOMER",
            "FIXED_END",
            daysAgo(5),
            Instant.now().plus(30, ChronoUnit.DAYS),
            Instant.now().plus(200, ChronoUnit.DAYS),
            "ACTIVE");
    TestData.Staff owner = owner();
    fixtures.startCustomerAgreement(
        owner.office().getId(),
        event,
        startPrice,
        daysAgo(1),
        Instant.now().plus(365, ChronoUnit.DAYS),
        "REGISTERED");
    mockMvc
        .perform(getRequest(URL).apply(cookieOf(owner)))
        .andExpect(jsonPath("$.priceKind").value("STANDARD"));
    jdbcTemplate.update("DELETE FROM billing_start_customer_agreement");
    fixtures.startCustomerAgreement(
        owner.office().getId(), event, startPrice, daysAgo(10), daysAgo(1), "APPROVED");
    mockMvc
        .perform(getRequest(URL).apply(cookieOf(owner)))
        .andExpect(jsonPath("$.priceKind").value("STANDARD"))
        .andExpect(jsonPath("$.priceVersion").value("2026-12-STD"));
  }

  @Test
  void 첫_청구_예정_시각이_미래이면_그_시각의_가격_버전을_고른다() throws Exception {
    fixtures.standardPrice("2026-12-STD", daysAgo(1));
    Instant firstBilling = Instant.now().plus(100, ChronoUnit.DAYS);
    fixtures.price(
        "2027-04-STD", "STANDARD", Instant.now().plus(50, ChronoUnit.DAYS), "ACTIVE", 44_900);
    TestData.Staff owner = owner();
    long event =
        fixtures.trialEvent(
            "GENERAL",
            "DURATION",
            daysAgo(5),
            Instant.now().plus(30, ChronoUnit.DAYS),
            null,
            "ACTIVE");
    fixtures
        .subscription(owner.office().getId())
        .status("TRIAL")
        .trial(event, daysAgo(1), firstBilling)
        .insert();
    mockMvc
        .perform(getRequest(URL).apply(cookieOf(owner)))
        .andExpect(jsonPath("$.priceVersion").value("2027-04-STD"))
        .andExpect(jsonPath("$.plans[0].estimatedAmount").value(44_900));
  }

  @Test
  void 시행_중인_요금표가_없으면_404_PRICE_SCHEDULE_NOT_PUBLISHED이다() throws Exception {
    assertError(
        mockMvc.perform(getRequest(URL).apply(cookieOf(owner()))),
        ErrorCode.PRICE_SCHEDULE_NOT_PUBLISHED);
  }

  @Test
  void 대표만_조회할_수_있다() throws Exception {
    fixtures.standardPrice("2026-12-STD", daysAgo(1));
    assertOwnerOnly(getRequest(URL));
  }
}
