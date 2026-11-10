package com.sairo.be.domain.billing.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.TestData;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@IntegrationTest
class StartCustomerAgreementIntegrationTest extends BillingIntegrationTestBase {

  private static final String URL = "/api/subscriptions/start-customer-agreement";
  private static final String ACK_URL = URL + "/acknowledge";

  private long startPrice;
  private long event;
  private Instant freeEnd;

  @BeforeEach
  void seed() {
    freeEnd = Instant.now().plus(200, ChronoUnit.DAYS);
    startPrice =
        fixtures.price(
            "2027-07-START",
            "START_CUSTOMER",
            Instant.now().minus(1, ChronoUnit.DAYS),
            "ACTIVE",
            29_900);
    event =
        fixtures.trialEvent(
            "START_CUSTOMER",
            "FIXED_END",
            Instant.now().minus(5, ChronoUnit.DAYS),
            Instant.now().plus(30, ChronoUnit.DAYS),
            freeEnd,
            "ACTIVE");
  }

  private long agreement(TestData.Staff owner, String status) {
    return fixtures.startCustomerAgreement(
        owner.office().getId(),
        event,
        startPrice,
        Instant.now().minus(1, ChronoUnit.DAYS),
        Instant.now().plus(365, ChronoUnit.DAYS),
        status);
  }

  @Test
  void 승인된_합의는_무료_종료_시각과_가격_버전과_현재_인원_기준_월_금액_미리보기를_준다() throws Exception {
    TestData.Staff owner = owner();
    memberIn(owner, OfficeMembershipRole.AGENT);
    memberIn(owner, OfficeMembershipRole.DEPUTY);
    long agreementId = agreement(owner, "APPROVED");
    mockMvc
        .perform(getRequest(URL).apply(cookieOf(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.agreementId").value(agreementId))
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.freeEndsAt").exists())
        .andExpect(jsonPath("$.priceVersion").value("2027-07-START"))
        .andExpect(jsonPath("$.priceStartsAt").exists())
        .andExpect(jsonPath("$.priceEndsAt").exists())
        .andExpect(jsonPath("$.monthlyAmountPreview.memberCount").value(3))
        .andExpect(jsonPath("$.monthlyAmountPreview.amountInclVat").value(49_900))
        .andExpect(jsonPath("$.conditions.weeklyUseConfirmed").value(true))
        .andExpect(jsonPath("$.conditions.monthlyInterviewConfirmed").value(true))
        .andExpect(jsonPath("$.conditions.casePublicationConsent").value(false))
        .andExpect(jsonPath("$.agreementTextVersion").value("v1"))
        .andExpect(jsonPath("$.contractedOn").exists())
        .andExpect(jsonPath("$.ownerAcknowledgedAt").value(nullValue()))
        .andExpect(jsonPath("$.version").value(1));
  }

  @Test
  void 합의가_없거나_등록만_됐거나_취소됐으면_조회는_404이다() throws Exception {
    TestData.Staff none = owner();
    assertError(
        mockMvc.perform(getRequest(URL).apply(cookieOf(none))), ErrorCode.RESOURCE_NOT_FOUND);
    TestData.Staff registered = owner();
    agreement(registered, "REGISTERED");
    assertError(
        mockMvc.perform(getRequest(URL).apply(cookieOf(registered))), ErrorCode.RESOURCE_NOT_FOUND);
    TestData.Staff revoked = owner();
    agreement(revoked, "REVOKED");
    assertError(
        mockMvc.perform(getRequest(URL).apply(cookieOf(revoked))), ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 다른_사무소의_합의는_보이지_않는다() throws Exception {
    agreement(owner(), "APPROVED");
    assertError(
        mockMvc.perform(getRequest(URL).apply(cookieOf(owner()))), ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 확인하면_확인_시각과_확인한_구성원을_남기고_버전을_올린다() throws Exception {
    TestData.Staff owner = owner();
    long agreementId = agreement(owner, "APPROVED");
    mockMvc
        .perform(postRequest(ACK_URL, "").apply(cookieOf(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ownerAcknowledgedAt").exists())
        .andExpect(jsonPath("$.version").value(2));
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT owner_acknowledged_by_membership_id, owner_acknowledged_at, status, price_ends_at"
                + " FROM billing_start_customer_agreement WHERE id = ?",
            agreementId);
    assertThat(row.get("owner_acknowledged_by_membership_id"))
        .isEqualTo(owner.membership().getId());
    assertThat(row.get("owner_acknowledged_at")).isNotNull();
    assertThat(row.get("status")).isEqualTo("APPROVED");
  }

  @Test
  void 이미_확인한_합의의_재확인은_최초_확인_시각을_바꾸지_않고_같은_응답을_준다() throws Exception {
    TestData.Staff owner = owner();
    agreement(owner, "APPROVED");
    String first =
        AuthFlowSupport.json(
                mockMvc
                    .perform(postRequest(ACK_URL, "").apply(cookieOf(owner)))
                    .andExpect(status().isOk())
                    .andReturn())
            .get("ownerAcknowledgedAt")
            .asString();
    mockMvc
        .perform(postRequest(ACK_URL, "").apply(cookieOf(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ownerAcknowledgedAt").value(first))
        .andExpect(jsonPath("$.version").value(2));
  }

  @Test
  void 확인은_자격과_가격과_무료_종료에_영향을_주지_않는다() throws Exception {
    TestData.Staff owner = owner();
    long agreementId = agreement(owner, "APPROVED");
    Map<String, Object> before =
        jdbcTemplate.queryForMap(
            "SELECT price_schedule_id, price_starts_at, price_ends_at, trial_event_id FROM"
                + " billing_start_customer_agreement WHERE id = ?",
            agreementId);
    mockMvc.perform(postRequest(ACK_URL, "").apply(cookieOf(owner))).andExpect(status().isOk());
    Map<String, Object> after =
        jdbcTemplate.queryForMap(
            "SELECT price_schedule_id, price_starts_at, price_ends_at, trial_event_id FROM"
                + " billing_start_customer_agreement WHERE id = ?",
            agreementId);
    assertThat(after).isEqualTo(before);
  }

  @Test
  void 승인_전_합의의_확인은_409_INVALID_TRANSITION이고_합의가_없거나_취소됐으면_404이다() throws Exception {
    TestData.Staff registered = owner();
    agreement(registered, "REGISTERED");
    assertError(
        mockMvc.perform(postRequest(ACK_URL, "").apply(cookieOf(registered))),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        mockMvc.perform(postRequest(ACK_URL, "").apply(cookieOf(owner()))),
        ErrorCode.RESOURCE_NOT_FOUND);
    TestData.Staff revoked = owner();
    agreement(revoked, "REVOKED");
    assertError(
        mockMvc.perform(postRequest(ACK_URL, "").apply(cookieOf(revoked))),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 합의_조회와_확인은_대표만_할_수_있다() throws Exception {
    assertOwnerOnly(getRequest(URL));
    assertOwnerOnly(postRequest(ACK_URL, ""));
  }
}
