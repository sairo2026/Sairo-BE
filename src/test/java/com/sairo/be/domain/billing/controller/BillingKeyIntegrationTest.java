package com.sairo.be.domain.billing.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StepUpState;
import com.sairo.be.global.support.TextCipher;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.BillingFixtures;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

@IntegrationTest
class BillingKeyIntegrationTest extends BillingIntegrationTestBase {

  private static final String SESSION_URL = "/api/subscriptions/billing-key/session";
  private static final String KEY_URL = "/api/subscriptions/billing-key";

  @Autowired
  @Qualifier("billingKeyCipher") private TextCipher billingKeyCipher;

  @BeforeEach
  void seedTerms() {
    fixtures.cardNoticeTerms(BillingFixtures.CARD_NOTICE_VERSION);
  }

  private TestData.Staff ownerWithSubscription() {
    TestData.Staff owner = owner();
    fixtures.subscription(owner.office().getId()).status("PRE_PAYMENT").insert();
    return owner;
  }

  private String openSession(Cookie cookie) throws Exception {
    return AuthFlowSupport.json(
            mockMvc
                .perform(postRequest(SESSION_URL, "{}").apply(cookie))
                .andExpect(status().isOk())
                .andReturn())
        .get("customerKey")
        .asString();
  }

  private static String registerBody(String customerKey, String noticeVersion) {
    return "{\"authKey\":\"auth-key-1\",\"customerKey\":\""
        + customerKey
        + "\",\"cardNoticeVersion\":\""
        + noticeVersion
        + "\"}";
  }

  @Test
  void 카드_등록_세션은_재인증이_없으면_401_STEP_UP_REQUIRED이다() throws Exception {
    TestData.Staff owner = ownerWithSubscription();
    assertError(
        mockMvc.perform(postRequest(SESSION_URL, "{}").apply(cookieOf(owner))),
        ErrorCode.STEP_UP_REQUIRED);
  }

  @Test
  void 카드_등록_세션은_무작위_고객_키와_마감_시각을_발급한다() throws Exception {
    TestData.Staff owner = ownerWithSubscription();
    Cookie cookie = cookieWithStepUp(owner);
    mockMvc
        .perform(postRequest(SESSION_URL, "{}").apply(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.customerKey").isString())
        .andExpect(jsonPath("$.expiresAt").exists());
    String first = openSession(cookie);
    String second = openSession(cookie);
    assertThat(first).isNotEqualTo(second).hasSizeGreaterThanOrEqualTo(32);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing_customer WHERE office_id = ?",
                Integer.class,
                owner.office().getId()))
        .isEqualTo(1);
  }

  @Test
  void 이미_카드가_등록된_사무소는_세션을_다시_열어도_고객_키가_바뀌지_않는다() throws Exception {
    TestData.Staff owner = ownerWithSubscription();
    fixtures.billingCustomer(owner.office().getId(), "stable-customer-key", true);
    Cookie cookie = cookieWithStepUp(owner);
    assertThat(openSession(cookie)).isEqualTo("stable-customer-key");
  }

  @Test
  void 카드를_등록하면_빌링키를_암호화해_저장하고_응답에는_빌링키를_주지_않는다() throws Exception {
    TestData.Staff owner = ownerWithSubscription();
    Cookie cookie = cookieWithStepUp(owner);
    String customerKey = openSession(cookie);
    String body =
        mockMvc
            .perform(postRequest(KEY_URL, registerBody(customerKey, "2026-12-01")).apply(cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.registered").value(true))
            .andExpect(jsonPath("$.registeredAt").exists())
            .andExpect(jsonPath("$.firstBillingScheduledAt").value(nullValue()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("billingKey").doesNotContain("stub-billing");

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT billing_key_encrypted, card_issuer, card_last4, customer_key_expires_at"
                + " FROM billing_customer WHERE office_id = ?",
            owner.office().getId());
    assertThat((String) row.get("billing_key_encrypted"))
        .isNotBlank()
        .doesNotContain("stub-billing");
    assertThat(row.get("card_issuer")).isEqualTo("STUB");
    assertThat(row.get("card_last4")).isEqualTo("5678");
    assertThat(row.get("customer_key_expires_at")).isNull();
    assertThat(paymentGateway.issuedCustomerKeys()).containsExactly(customerKey);
  }

  @Test
  void 카드_등록은_안내_확인_이력을_사무소와_함께_남기고_구독_버전을_올리며_재인증을_소비한다() throws Exception {
    TestData.Staff owner = ownerWithSubscription();
    Cookie cookie = cookieWithStepUp(owner);
    String customerKey = openSession(cookie);
    mockMvc
        .perform(postRequest(KEY_URL, registerBody(customerKey, "2026-12-01")).apply(cookie))
        .andExpect(status().isOk());
    List<Map<String, Object>> agreements =
        jdbcTemplate.queryForList(
            "SELECT terms_code, terms_version, agreed, consent_path, office_id, user_id"
                + " FROM terms_agreement WHERE consent_path = 'CARD_REGISTRATION'");
    assertThat(agreements).hasSize(1);
    assertThat(agreements.get(0))
        .containsEntry("terms_code", "CARD_NOTICE")
        .containsEntry("terms_version", "2026-12-01")
        .containsEntry("agreed", true)
        .containsEntry("office_id", owner.office().getId())
        .containsEntry("user_id", owner.user().getId());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT version FROM subscription WHERE office_id = ?",
                Long.class,
                owner.office().getId()))
        .isEqualTo(2L);
    assertThat(sessionAttribute(cookie, StepUpState.STEP_UP_UNTIL_ATTRIBUTE)).isNull();
    assertError(
        mockMvc.perform(
            postRequest(KEY_URL, registerBody(customerKey, "2026-12-01")).apply(cookie)),
        ErrorCode.STEP_UP_REQUIRED);
  }

  @Test
  void 카드_등록은_기존_카드를_새_카드로_교체하고_이전_키를_보관한다() throws Exception {
    TestData.Staff owner = ownerWithSubscription();
    fixtures.billingCustomer(owner.office().getId(), "stable-customer-key", true);
    Cookie cookie = cookieWithStepUp(owner);
    String customerKey = openSession(cookie);
    mockMvc
        .perform(postRequest(KEY_URL, registerBody(customerKey, "2026-12-01")).apply(cookie))
        .andExpect(status().isOk());
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT billing_key_encrypted, previous_billing_key_encrypted, card_issuer,"
                + " replaced_at FROM billing_customer WHERE office_id = ?",
            owner.office().getId());
    assertThat(row.get("previous_billing_key_encrypted")).isEqualTo("enc-existing");
    assertThat(row.get("billing_key_encrypted")).isNotEqualTo("enc-existing");
    assertThat(row.get("card_issuer")).isEqualTo("STUB");
    assertThat(row.get("replaced_at")).isNotNull();
  }

  @Test
  void 카드_등록은_세션에서_발급한_고객_키와_다르면_400이고_fieldErrors에_customerKey를_담는다() throws Exception {
    TestData.Staff owner = ownerWithSubscription();
    Cookie cookie = cookieWithStepUp(owner);
    openSession(cookie);
    assertFieldError(
        mockMvc.perform(
            postRequest(KEY_URL, registerBody("other-key", "2026-12-01")).apply(cookie)),
        "customerKey",
        "INVALID_VALUE");
    assertThat(paymentGateway.issuedCustomerKeys()).isEmpty();
  }

  @Test
  void 카드_등록은_세션을_열지_않았거나_고객_키가_만료됐으면_400이다() throws Exception {
    TestData.Staff owner = ownerWithSubscription();
    Cookie cookie = cookieWithStepUp(owner);
    assertFieldError(
        mockMvc.perform(postRequest(KEY_URL, registerBody("none", "2026-12-01")).apply(cookie)),
        "customerKey",
        "INVALID_VALUE");
    String customerKey = openSession(cookie);
    jdbcTemplate.update(
        "UPDATE billing_customer SET customer_key_expires_at = ?, customer_key_issued_at = ?"
            + " WHERE office_id = ?",
        java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)),
        java.sql.Timestamp.from(Instant.now().minus(20, ChronoUnit.MINUTES)),
        owner.office().getId());
    assertFieldError(
        mockMvc.perform(
            postRequest(KEY_URL, registerBody(customerKey, "2026-12-01")).apply(cookie)),
        "customerKey",
        "INVALID_VALUE");
  }

  @Test
  void 카드_등록은_카드_등록_안내_버전이_시행_버전과_다르면_409이고_바뀐_항목을_알려준다() throws Exception {
    TestData.Staff owner = ownerWithSubscription();
    Cookie cookie = cookieWithStepUp(owner);
    String customerKey = openSession(cookie);
    assertError(
            mockMvc.perform(
                postRequest(KEY_URL, registerBody(customerKey, "2025-01-01")).apply(cookie)),
            ErrorCode.TERMS_VERSION_CHANGED)
        .andExpect(jsonPath("$.changedItems[0].termsCode").value("CARD_NOTICE"))
        .andExpect(jsonPath("$.changedItems[0].submittedVersion").value("2025-01-01"))
        .andExpect(jsonPath("$.changedItems[0].currentVersion").value("2026-12-01"));
    assertThat(paymentGateway.issuedCustomerKeys()).isEmpty();
  }

  @Test
  void 빌링키_발급이_확정_실패하면_402_BILLING_KEY_ISSUE_FAILED이고_저장하지_않는다() throws Exception {
    TestData.Staff owner = ownerWithSubscription();
    Cookie cookie = cookieWithStepUp(owner);
    String customerKey = openSession(cookie);
    paymentGateway.failNextBillingKeyIssue();
    assertError(
        mockMvc.perform(
            postRequest(KEY_URL, registerBody(customerKey, "2026-12-01")).apply(cookie)),
        ErrorCode.BILLING_KEY_ISSUE_FAILED);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT billing_key_encrypted FROM billing_customer WHERE office_id = ?",
                String.class,
                owner.office().getId()))
        .isNull();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM terms_agreement WHERE consent_path = 'CARD_REGISTRATION'",
                Integer.class))
        .isZero();
  }

  @Test
  void 카드_등록은_필수_필드가_없거나_비어_있으면_400이다() throws Exception {
    TestData.Staff owner = ownerWithSubscription();
    Cookie cookie = cookieWithStepUp(owner);
    assertError(
        mockMvc.perform(postRequest(KEY_URL, "{\"authKey\":\"a\"}").apply(cookie)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(
            postRequest(
                    KEY_URL, "{\"authKey\":\"\",\"customerKey\":\"k\",\"cardNoticeVersion\":\"v\"}")
                .apply(cookie)),
        ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 카드_등록은_재인증이_없으면_401_STEP_UP_REQUIRED이다() throws Exception {
    TestData.Staff owner = ownerWithSubscription();
    assertError(
        mockMvc.perform(
            postRequest(KEY_URL, registerBody("k", "2026-12-01")).apply(cookieOf(owner))),
        ErrorCode.STEP_UP_REQUIRED);
  }

  @Test
  void 카드_해제는_204이고_해제_뒤_결제수단이_없다() throws Exception {
    TestData.Staff owner = ownerWithSubscription();
    fixtures.billingCustomer(owner.office().getId(), "stable-customer-key", true);
    Cookie cookie = cookieWithStepUp(owner);
    mockMvc.perform(deleteRequest(KEY_URL).apply(cookie)).andExpect(status().isNoContent());
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT billing_key_encrypted, card_last4, previous_billing_key_encrypted"
                + " FROM billing_customer WHERE office_id = ?",
            owner.office().getId());
    assertThat(row.get("billing_key_encrypted")).isNull();
    assertThat(row.get("card_last4")).isNull();
    assertThat(row.get("previous_billing_key_encrypted")).isEqualTo("enc-existing");
    mockMvc
        .perform(getRequest("/api/subscriptions/current").apply(cookieOf(owner)))
        .andExpect(jsonPath("$.paymentMethod").value(nullValue()))
        .andExpect(jsonPath("$.readiness.hasBillingKey").value(false));
  }

  @Test
  void 카드_해제는_결제대행사의_빌링키도_삭제한다() throws Exception {
    TestData.Staff owner = ownerWithSubscription();
    fixtures.billingCustomerWithKey(
        owner.office().getId(),
        "stable-customer-key",
        billingKeyCipher.encrypt("plain-billing-key"));
    Cookie cookie = cookieWithStepUp(owner);

    mockMvc.perform(deleteRequest(KEY_URL).apply(cookie)).andExpect(status().isNoContent());

    assertThat(paymentGateway.deletedBillingKeys()).containsExactly("plain-billing-key");
  }

  @Test
  void 카드_해제는_등록된_카드가_없어도_204이고_재인증이_없으면_401이다() throws Exception {
    TestData.Staff owner = ownerWithSubscription();
    mockMvc
        .perform(deleteRequest(KEY_URL).apply(cookieWithStepUp(owner)))
        .andExpect(status().isNoContent());
    assertError(
        mockMvc.perform(deleteRequest(KEY_URL).apply(cookieOf(owner))), ErrorCode.STEP_UP_REQUIRED);
  }

  @Test
  void 카드_세션_등록_해제는_대표만_할_수_있다() throws Exception {
    assertOwnerOnly(postRequest(SESSION_URL, "{}"));
    assertOwnerOnly(postRequest(KEY_URL, registerBody("k", "2026-12-01")));
    assertOwnerOnly(deleteRequest(KEY_URL));
  }

  @Test
  void 다른_사무소의_고객_키로는_카드를_등록할_수_없다() throws Exception {
    TestData.Staff other = ownerWithSubscription();
    String otherKey = openSession(cookieWithStepUp(other));
    TestData.Staff mine = ownerWithSubscription();
    Cookie cookie = cookieWithStepUp(mine);
    openSession(cookie);
    assertFieldError(
        mockMvc.perform(postRequest(KEY_URL, registerBody(otherKey, "2026-12-01")).apply(cookie)),
        "customerKey",
        "INVALID_VALUE");
  }
}
