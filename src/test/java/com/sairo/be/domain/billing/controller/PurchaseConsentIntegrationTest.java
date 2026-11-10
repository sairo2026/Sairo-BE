package com.sairo.be.domain.billing.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.BillingFixtures;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class PurchaseConsentIntegrationTest extends BillingIntegrationTestBase {

  private static final String PREVIEW_URL = "/api/subscriptions/purchase-consent/preview";
  private static final String CONSENT_URL = "/api/subscriptions/purchase-consent";

  @BeforeEach
  void seedCatalog() {
    fixtures.standardPrice("2026-12-STD", Instant.now().minus(1, ChronoUnit.DAYS));
    fixtures.purchaseTerms(BillingFixtures.PURCHASE_TERMS_VERSION);
  }

  private TestData.Staff trialOwner(Instant firstBilling) {
    TestData.Staff owner = owner();
    long event =
        fixtures.trialEvent(
            "GENERAL",
            "DURATION",
            Instant.now().minus(5, ChronoUnit.DAYS),
            Instant.now().plus(30, ChronoUnit.DAYS),
            null,
            "ACTIVE");
    fixtures
        .subscription(owner.office().getId())
        .status("TRIAL")
        .trial(event, Instant.now().minus(3, ChronoUnit.DAYS), firstBilling)
        .insert();
    return owner;
  }

  private TestData.Staff prePaymentOwner() {
    TestData.Staff owner = owner();
    fixtures
        .subscription(owner.office().getId())
        .status("PRE_PAYMENT")
        .prePaymentSince(Instant.now().minus(1, ChronoUnit.DAYS))
        .insert();
    return owner;
  }

  private JsonNode preview(Cookie cookie) throws Exception {
    return AuthFlowSupport.json(
        mockMvc
            .perform(getRequest(PREVIEW_URL).apply(cookie))
            .andExpect(status().isOk())
            .andReturn());
  }

  private static String consentBody(JsonNode preview, UnaryOperator<Map<String, Object>> edit) {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("termsVersion", preview.get("termsVersion").asString());
    body.put("plan", preview.get("plan").asString());
    body.put("memberCount", preview.get("memberCount").asInt());
    body.put("priceVersion", preview.get("priceVersion").asString());
    body.put("totalAmountInclVat", preview.get("totalAmountInclVat").asLong());
    body.put("amountLimit", preview.get("amountLimit").asLong());
    body.put("periodStart", preview.get("periodStart").asString());
    body.put("periodEnd", preview.get("periodEnd").asString());
    body.put("nextChargeAt", preview.get("nextChargeAt").asString());
    body.put("agreed", true);
    return toJson(edit.apply(body));
  }

  private static String consentBody(JsonNode preview) {
    return consentBody(preview, UnaryOperator.identity());
  }

  private static String toJson(Map<String, Object> body) {
    StringBuilder json = new StringBuilder("{");
    body.forEach(
        (key, value) -> {
          if (json.length() > 1) {
            json.append(',');
          }
          json.append('"').append(key).append("\":");
          if (value instanceof String text) {
            json.append('"').append(text).append('"');
          } else {
            json.append(value);
          }
        });
    return json.append('}').toString();
  }

  @Test
  void 무료체험_중_미리보기는_첫_청구_예정_시각을_회차_시작으로_계산한다() throws Exception {
    Instant firstBilling = Instant.parse("2027-07-01T00:00:00+09:00");
    TestData.Staff owner = trialOwner(firstBilling);
    mockMvc
        .perform(getRequest(PREVIEW_URL).apply(cookieOf(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.termsVersion").value("2026-12-01"))
        .andExpect(jsonPath("$.plan").value("SOLO"))
        .andExpect(jsonPath("$.memberCount").value(1))
        .andExpect(jsonPath("$.priceVersion").value("2026-12-STD"))
        .andExpect(jsonPath("$.priceChangeNoticeId").value(nullValue()))
        .andExpect(jsonPath("$.totalAmountInclVat").value(39_900))
        .andExpect(jsonPath("$.vatAmount").value(3_627))
        .andExpect(jsonPath("$.amountLimit").value(39_900))
        .andExpect(jsonPath("$.periodStart").value("2027-07-01T00:00:00+09:00"))
        .andExpect(jsonPath("$.periodEnd").value("2027-08-01T00:00:00+09:00"))
        .andExpect(jsonPath("$.nextChargeAt").value("2027-08-01T00:00:00+09:00"))
        .andExpect(jsonPath("$.firstBillingScheduledAt").value("2027-07-01T00:00:00+09:00"))
        .andExpect(jsonPath("$.autoRenewal").value(true))
        .andExpect(jsonPath("$.cancellationTerms").isNotEmpty())
        .andExpect(jsonPath("$.refundTerms").isNotEmpty());
  }

  @Test
  void 회차_기준일이_없는_달은_말일을_쓰고_다음_달에는_원래_일자로_돌아간다() throws Exception {
    Instant firstBilling = Instant.parse("2027-01-31T09:00:00+09:00");
    TestData.Staff owner = trialOwner(firstBilling);
    mockMvc
        .perform(getRequest(PREVIEW_URL).apply(cookieOf(owner)))
        .andExpect(jsonPath("$.periodStart").value("2027-01-31T09:00:00+09:00"))
        .andExpect(jsonPath("$.periodEnd").value("2027-02-28T09:00:00+09:00"));
  }

  @Test
  void 결제_전_상태_미리보기는_현재_시각을_회차_시작으로_하고_첫_청구_예정_시각이_없다() throws Exception {
    TestData.Staff owner = prePaymentOwner();
    mockMvc
        .perform(getRequest(PREVIEW_URL).apply(cookieOf(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.firstBillingScheduledAt").value(nullValue()))
        .andExpect(jsonPath("$.totalAmountInclVat").value(39_900))
        .andExpect(jsonPath("$.periodStart").exists())
        .andExpect(jsonPath("$.periodEnd").exists());
  }

  @Test
  void 미리보기는_현재_과금_인원으로_요금제와_금액과_세액을_계산한다() throws Exception {
    TestData.Staff owner = prePaymentOwner();
    memberIn(owner, OfficeMembershipRole.AGENT);
    memberIn(owner, OfficeMembershipRole.DEPUTY);
    mockMvc
        .perform(getRequest(PREVIEW_URL).apply(cookieOf(owner)))
        .andExpect(jsonPath("$.plan").value("TEAM"))
        .andExpect(jsonPath("$.memberCount").value(3))
        .andExpect(jsonPath("$.totalAmountInclVat").value(59_900))
        .andExpect(jsonPath("$.vatAmount").value(5_445))
        .andExpect(jsonPath("$.amountLimit").value(59_900));
  }

  @Test
  void 미리보기_plan은_SOLO_TEAM만_받고_ENTERPRISE는_400이다() throws Exception {
    TestData.Staff owner = prePaymentOwner();
    Cookie cookie = cookieOf(owner);
    mockMvc
        .perform(getRequest(PREVIEW_URL + "?plan=TEAM").apply(cookie))
        .andExpect(status().isOk());
    mockMvc
        .perform(getRequest(PREVIEW_URL + "?plan=SOLO").apply(cookie))
        .andExpect(status().isOk());
    assertFieldError(
        mockMvc.perform(getRequest(PREVIEW_URL + "?plan=ENTERPRISE").apply(cookie)),
        "plan",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(getRequest(PREVIEW_URL + "?plan=FOO").apply(cookie)),
        "plan",
        "NOT_ALLOWED");
  }

  @Test
  void 구독이_없거나_구매_약관이_게시되지_않았으면_미리보기는_404이다() throws Exception {
    assertError(
        mockMvc.perform(getRequest(PREVIEW_URL).apply(cookieOf(owner()))),
        ErrorCode.RESOURCE_NOT_FOUND);
    jdbcTemplate.update("DELETE FROM terms_catalog WHERE terms_code = 'SUBSCRIPTION_PURCHASE'");
    assertError(
        mockMvc.perform(getRequest(PREVIEW_URL).apply(cookieOf(prePaymentOwner()))),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 동의를_저장하면_동의_이벤트와_구매_동의_행을_함께_만들고_구독_버전을_올린다() throws Exception {
    TestData.Staff owner = trialOwner(Instant.now().plus(40, ChronoUnit.DAYS));
    Cookie cookie = cookieOf(owner);
    JsonNode preview = preview(cookie);
    mockMvc
        .perform(postRequest(CONSENT_URL, consentBody(preview)).apply(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.termsVersion").value("2026-12-01"))
        .andExpect(jsonPath("$.totalAmountInclVat").value(39_900))
        .andExpect(jsonPath("$.vatAmount").value(3_627))
        .andExpect(jsonPath("$.amountLimit").value(39_900))
        .andExpect(jsonPath("$.agreedAt").exists());

    Map<String, Object> agreement =
        jdbcTemplate.queryForMap(
            "SELECT id, user_id, terms_code, terms_version, agreed, consent_path, office_id"
                + " FROM terms_agreement WHERE consent_path = 'PURCHASE_CONSENT'");
    assertThat(agreement)
        .containsEntry("terms_code", "SUBSCRIPTION_PURCHASE")
        .containsEntry("terms_version", "2026-12-01")
        .containsEntry("agreed", true)
        .containsEntry("office_id", owner.office().getId())
        .containsEntry("user_id", owner.user().getId());
    Map<String, Object> consent =
        jdbcTemplate.queryForMap(
            "SELECT terms_agreement_id, plan, member_count, total_amount_incl_vat, vat_amount,"
                + " amount_limit, agreed FROM subscription_purchase_consent");
    assertThat(consent)
        .containsEntry("terms_agreement_id", agreement.get("id"))
        .containsEntry("plan", "SOLO")
        .containsEntry("member_count", 1)
        .containsEntry("total_amount_incl_vat", 39_900L)
        .containsEntry("vat_amount", 3_627L)
        .containsEntry("amount_limit", 39_900L)
        .containsEntry("agreed", true);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT version FROM subscription WHERE office_id = ?",
                Long.class,
                owner.office().getId()))
        .isEqualTo(2L);
    mockMvc
        .perform(getRequest("/api/subscriptions/current").apply(cookie))
        .andExpect(jsonPath("$.readiness.hasValidConsent").value(true))
        .andExpect(jsonPath("$.status").value("TRIAL"));
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing_invoice", Integer.class))
        .isZero();
  }

  @Test
  void 결제_전_상태의_동의는_현재_시각_기준_회차로_허용_오차_안에서_저장한다() throws Exception {
    TestData.Staff owner = prePaymentOwner();
    Cookie cookie = cookieOf(owner);
    JsonNode preview = preview(cookie);
    mockMvc
        .perform(postRequest(CONSENT_URL, consentBody(preview)).apply(cookie))
        .andExpect(status().isOk());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM subscription_purchase_consent", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void 동의_약관_버전이_시행_버전과_다르면_409_TERMS_VERSION_CHANGED이다() throws Exception {
    TestData.Staff owner = prePaymentOwner();
    Cookie cookie = cookieOf(owner);
    JsonNode preview = preview(cookie);
    assertError(
            mockMvc.perform(
                postRequest(
                        CONSENT_URL,
                        consentBody(preview, body -> with(body, "termsVersion", "2025-01-01")))
                    .apply(cookie)),
            ErrorCode.TERMS_VERSION_CHANGED)
        .andExpect(jsonPath("$.changedItems[0].termsCode").value("SUBSCRIPTION_PURCHASE"))
        .andExpect(jsonPath("$.changedItems[0].currentVersion").value("2026-12-01"));
    assertNoConsentSaved();
  }

  @Test
  void 화면_값이_서버_재계산값과_다르면_409_CONSENT_TERMS_MISMATCH이고_아무것도_저장하지_않는다() throws Exception {
    TestData.Staff owner = trialOwner(Instant.now().plus(40, ChronoUnit.DAYS));
    Cookie cookie = cookieOf(owner);
    JsonNode preview = preview(cookie);
    String shiftedStart =
        java.time.OffsetDateTime.parse(preview.get("periodStart").asString())
            .plusDays(1)
            .toString();
    List<UnaryOperator<Map<String, Object>>> edits =
        List.of(
            body -> with(body, "totalAmountInclVat", 29_900L),
            body -> with(body, "amountLimit", 49_900L),
            body -> with(body, "memberCount", 3),
            body -> with(body, "plan", "TEAM"),
            body -> with(body, "priceVersion", "OTHER-V"),
            body -> with(body, "periodStart", shiftedStart),
            body -> with(body, "nextChargeAt", shiftedStart));
    for (UnaryOperator<Map<String, Object>> edit : edits) {
      assertError(
          mockMvc.perform(postRequest(CONSENT_URL, consentBody(preview, edit)).apply(cookie)),
          ErrorCode.CONSENT_TERMS_MISMATCH);
    }
    assertNoConsentSaved();
  }

  @Test
  void 결제_전_상태에서_시작_시각이_허용_오차를_넘으면_불일치이다() throws Exception {
    TestData.Staff owner = prePaymentOwner();
    Cookie cookie = cookieOf(owner);
    JsonNode preview = preview(cookie);
    java.time.OffsetDateTime shifted =
        java.time.OffsetDateTime.parse(preview.get("periodStart").asString()).minusHours(2);
    assertError(
        mockMvc.perform(
            postRequest(
                    CONSENT_URL,
                    consentBody(
                        preview,
                        body -> {
                          Map<String, Object> edited =
                              with(body, "periodStart", shifted.toString());
                          return with(
                              with(edited, "periodEnd", shifted.plusMonths(1).toString()),
                              "nextChargeAt",
                              shifted.plusMonths(1).toString());
                        }))
                .apply(cookie)),
        ErrorCode.CONSENT_TERMS_MISMATCH);
  }

  @Test
  void 미리보기_뒤_인원이_늘면_동의는_불일치로_거절된다() throws Exception {
    TestData.Staff owner = prePaymentOwner();
    Cookie cookie = cookieOf(owner);
    JsonNode preview = preview(cookie);
    memberIn(owner, OfficeMembershipRole.AGENT);
    assertError(
        mockMvc.perform(postRequest(CONSENT_URL, consentBody(preview)).apply(cookie)),
        ErrorCode.CONSENT_TERMS_MISMATCH);
    assertNoConsentSaved();
  }

  @Test
  void 동의_요청은_agreed가_true가_아니거나_필수_필드가_없거나_기업_요금제이면_400이다() throws Exception {
    TestData.Staff owner = prePaymentOwner();
    Cookie cookie = cookieOf(owner);
    JsonNode preview = preview(cookie);
    assertError(
        mockMvc.perform(
            postRequest(CONSENT_URL, consentBody(preview, body -> with(body, "agreed", false)))
                .apply(cookie)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(
            postRequest(CONSENT_URL, "{\"termsVersion\":\"2026-12-01\"}").apply(cookie)),
        ErrorCode.INVALID_REQUEST);
    assertFieldError(
        mockMvc.perform(
            postRequest(CONSENT_URL, consentBody(preview, body -> with(body, "plan", "ENTERPRISE")))
                .apply(cookie)),
        "plan",
        "NOT_ALLOWED");
    assertNoConsentSaved();
  }

  @Test
  void 구독이_없으면_동의는_404이다() throws Exception {
    TestData.Staff withSubscription = prePaymentOwner();
    JsonNode preview = preview(cookieOf(withSubscription));
    assertError(
        mockMvc.perform(postRequest(CONSENT_URL, consentBody(preview)).apply(cookieOf(owner()))),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 미리보기와_동의는_대표만_할_수_있다() throws Exception {
    assertOwnerOnly(getRequest(PREVIEW_URL));
    assertOwnerOnly(
        postRequest(
            CONSENT_URL,
            "{\"termsVersion\":\"v\",\"plan\":\"SOLO\",\"memberCount\":1,\"totalAmountInclVat\":1,"
                + "\"amountLimit\":1,\"periodStart\":\"2027-01-01T00:00:00+09:00\","
                + "\"periodEnd\":\"2027-02-01T00:00:00+09:00\","
                + "\"nextChargeAt\":\"2027-02-01T00:00:00+09:00\",\"agreed\":true}"));
  }

  private void assertNoConsentSaved() {
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM subscription_purchase_consent", Integer.class))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM terms_agreement WHERE consent_path = 'PURCHASE_CONSENT'",
                Integer.class))
        .isZero();
  }

  private static Map<String, Object> with(Map<String, Object> body, String key, Object value) {
    body.put(key, value);
    return body;
  }
}
