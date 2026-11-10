package com.sairo.be.domain.billing.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.BillingFixtures;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class PriceChangeNoticeIntegrationTest extends BillingIntegrationTestBase {

  private static final String LIST_URL = "/api/subscriptions/price-change-notices";
  private static final String PREVIEW_URL = "/api/subscriptions/purchase-consent/preview";
  private static final String CONSENT_URL = "/api/subscriptions/purchase-consent";

  private long fromPrice;
  private long toPrice;
  private TestData.Staff owner;
  private long subscriptionId;

  @BeforeEach
  void seed() {
    fixtures.purchaseTerms(BillingFixtures.PURCHASE_TERMS_VERSION);
    fromPrice = fixtures.standardPrice("2026-12-STD", Instant.now().minus(30, ChronoUnit.DAYS));
    toPrice =
        fixtures.price(
            "2027-09-STD", "STANDARD", Instant.now().plus(25, ChronoUnit.DAYS), "ACTIVE", 44_900);
    owner = owner();
    subscriptionId =
        fixtures
            .subscription(owner.office().getId())
            .status("ACTIVE")
            .period(
                Instant.now().minus(10, ChronoUnit.DAYS), Instant.now().plus(20, ChronoUnit.DAYS))
            .insert();
  }

  private long notice() {
    return fixtures.notice(owner.office().getId(), subscriptionId, fromPrice, toPrice).insert();
  }

  @Test
  void 목록은_자기_사무소_안내를_최근_안내_순으로_주고_가격_버전과_동의_필요_여부를_담는다() throws Exception {
    long first = notice();
    long other =
        fixtures.price(
            "2028-01-STD", "STANDARD", Instant.now().plus(90, ChronoUnit.DAYS), "ACTIVE", 49_900);
    long second =
        fixtures
            .notice(owner.office().getId(), subscriptionId, fromPrice, other)
            .nonIncrease()
            .dueAt(Instant.now().plus(40, ChronoUnit.DAYS))
            .insert();
    TestData.Staff stranger = owner();
    long strangerSubscription =
        fixtures.subscription(stranger.office().getId()).status("ACTIVE").insert();
    fixtures.notice(stranger.office().getId(), strangerSubscription, fromPrice, toPrice).insert();
    mockMvc
        .perform(getRequest(LIST_URL).apply(cookieOf(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.size").value(25))
        .andExpect(jsonPath("$.items[0].noticeId").value(first))
        .andExpect(jsonPath("$.items[0].changeKind").value("INCREASE"))
        .andExpect(jsonPath("$.items[0].fromPriceVersion").value("2026-12-STD"))
        .andExpect(jsonPath("$.items[0].toPriceVersion").value("2027-09-STD"))
        .andExpect(jsonPath("$.items[0].memberCount").value(3))
        .andExpect(jsonPath("$.items[0].currentAmountInclVat").value(49_900))
        .andExpect(jsonPath("$.items[0].newAmountInclVat").value(59_900))
        .andExpect(jsonPath("$.items[0].decision").value("PENDING"))
        .andExpect(jsonPath("$.items[0].decidedAt").value(nullValue()))
        .andExpect(jsonPath("$.items[0].consentRequired").value(true))
        .andExpect(jsonPath("$.items[0].notifiedAt").exists())
        .andExpect(jsonPath("$.items[0].effectiveAt").exists())
        .andExpect(jsonPath("$.items[0].appliesFromPeriodStart").exists())
        .andExpect(jsonPath("$.items[0].responseDueAt").exists())
        .andExpect(jsonPath("$.items[1].noticeId").value(second))
        .andExpect(jsonPath("$.items[1].changeKind").value("NON_INCREASE"))
        .andExpect(jsonPath("$.items[1].decision").value("NOT_REQUIRED"))
        .andExpect(jsonPath("$.items[1].consentRequired").value(false));
  }

  @Test
  void 목록의_page와_size가_잘못되면_400이다() throws Exception {
    assertError(
        mockMvc.perform(getRequest(LIST_URL + "?size=7").apply(cookieOf(owner))),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(getRequest(LIST_URL + "?page=-3").apply(cookieOf(owner))),
        ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 인상_안내를_거절하면_거절_결정과_구독_종료_예정_시각을_주고_구독_버전을_올린다() throws Exception {
    long noticeId = notice();
    mockMvc
        .perform(postRequest(LIST_URL + "/" + noticeId + "/decline", "").apply(cookieOf(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.noticeId").value(noticeId))
        .andExpect(jsonPath("$.decision").value("DECLINED"))
        .andExpect(jsonPath("$.decidedAt").exists())
        .andExpect(jsonPath("$.subscriptionEndsAt").exists());
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT decision, decided_by_membership_id, decided_at FROM pricing_change_notice WHERE id = ?",
            noticeId);
    assertThat(row.get("decision")).isEqualTo("DECLINED");
    assertThat(row.get("decided_by_membership_id")).isEqualTo(owner.membership().getId());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT version FROM subscription WHERE id = ?", Long.class, subscriptionId))
        .isEqualTo(2L);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM subscription WHERE id = ?", String.class, subscriptionId))
        .isEqualTo("ACTIVE");
  }

  @Test
  void 동의가_필요_없는_안내의_거절은_409_INVALID_TRANSITION이다() throws Exception {
    long noticeId =
        fixtures
            .notice(owner.office().getId(), subscriptionId, fromPrice, toPrice)
            .nonIncrease()
            .insert();
    assertError(
        mockMvc.perform(
            postRequest(LIST_URL + "/" + noticeId + "/decline", "").apply(cookieOf(owner))),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 응답_기한이_지났거나_이미_결정된_안내의_거절은_409_PRICE_CHANGE_NOTICE_CLOSED이다() throws Exception {
    long expired =
        fixtures
            .notice(owner.office().getId(), subscriptionId, fromPrice, toPrice)
            .dueAt(Instant.now().minus(1, ChronoUnit.HOURS))
            .insert();
    assertError(
        mockMvc.perform(
            postRequest(LIST_URL + "/" + expired + "/decline", "").apply(cookieOf(owner))),
        ErrorCode.PRICE_CHANGE_NOTICE_CLOSED);
    long other =
        fixtures.price(
            "2028-01-STD", "STANDARD", Instant.now().plus(90, ChronoUnit.DAYS), "ACTIVE", 49_900);
    long declined =
        fixtures
            .notice(owner.office().getId(), subscriptionId, fromPrice, other)
            .decided("DECLINED", owner.membership().getId(), null)
            .insert();
    assertError(
        mockMvc.perform(
            postRequest(LIST_URL + "/" + declined + "/decline", "").apply(cookieOf(owner))),
        ErrorCode.PRICE_CHANGE_NOTICE_CLOSED);
  }

  @Test
  void 없거나_다른_사무소의_안내_거절은_404이고_경로_값이_숫자가_아니면_400이다() throws Exception {
    TestData.Staff stranger = owner();
    long strangerSubscription =
        fixtures.subscription(stranger.office().getId()).status("ACTIVE").insert();
    long strangerNotice =
        fixtures
            .notice(stranger.office().getId(), strangerSubscription, fromPrice, toPrice)
            .insert();
    assertError(
        mockMvc.perform(
            postRequest(LIST_URL + "/" + strangerNotice + "/decline", "").apply(cookieOf(owner))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(postRequest(LIST_URL + "/999999/decline", "").apply(cookieOf(owner))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(postRequest(LIST_URL + "/abc/decline", "").apply(cookieOf(owner))),
        ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 안내_ID를_담은_미리보기는_새_가격_버전과_새_가격_첫_청구기간으로_계산한다() throws Exception {
    long noticeId = notice();
    mockMvc
        .perform(
            getRequest(PREVIEW_URL + "?priceChangeNoticeId=" + noticeId).apply(cookieOf(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.priceChangeNoticeId").value(noticeId))
        .andExpect(jsonPath("$.priceVersion").value("2027-09-STD"))
        .andExpect(jsonPath("$.totalAmountInclVat").value(44_900))
        .andExpect(jsonPath("$.amountLimit").value(44_900))
        .andExpect(jsonPath("$.periodStart").exists());
  }

  @Test
  void 닫혔거나_없는_안내_ID의_미리보기는_409_또는_404이다() throws Exception {
    long expired =
        fixtures
            .notice(owner.office().getId(), subscriptionId, fromPrice, toPrice)
            .dueAt(Instant.now().minus(1, ChronoUnit.HOURS))
            .insert();
    assertError(
        mockMvc.perform(
            getRequest(PREVIEW_URL + "?priceChangeNoticeId=" + expired).apply(cookieOf(owner))),
        ErrorCode.PRICE_CHANGE_NOTICE_CLOSED);
    assertError(
        mockMvc.perform(
            getRequest(PREVIEW_URL + "?priceChangeNoticeId=999999").apply(cookieOf(owner))),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 안내_ID와_함께_동의하면_새_동의_행을_저장하고_안내_결정을_AGREED로_바꾼다() throws Exception {
    long noticeId = notice();
    Cookie cookie = cookieOf(owner);
    JsonNode preview =
        AuthFlowSupport.json(
            mockMvc
                .perform(getRequest(PREVIEW_URL + "?priceChangeNoticeId=" + noticeId).apply(cookie))
                .andExpect(status().isOk())
                .andReturn());
    mockMvc
        .perform(postRequest(CONSENT_URL, consentBody(preview, noticeId, true)).apply(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalAmountInclVat").value(44_900))
        .andExpect(jsonPath("$.amountLimit").value(44_900));
    Map<String, Object> notice =
        jdbcTemplate.queryForMap(
            "SELECT decision, decided_by_membership_id, purchase_consent_id FROM pricing_change_notice"
                + " WHERE id = ?",
            noticeId);
    assertThat(notice.get("decision")).isEqualTo("AGREED");
    assertThat(notice.get("decided_by_membership_id")).isEqualTo(owner.membership().getId());
    Long consentId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM subscription_purchase_consent ORDER BY id DESC LIMIT 1", Long.class);
    assertThat(notice.get("purchase_consent_id")).isEqualTo(consentId);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT total_amount_incl_vat FROM subscription_purchase_consent WHERE id = ?",
                Long.class,
                consentId))
        .isEqualTo(44_900L);
  }

  @Test
  void 안내_ID와_함께_동의했는데_안내가_닫혔으면_409이고_동의를_저장하지_않는다() throws Exception {
    long noticeId = notice();
    Cookie cookie = cookieOf(owner);
    JsonNode preview =
        AuthFlowSupport.json(
            mockMvc
                .perform(getRequest(PREVIEW_URL + "?priceChangeNoticeId=" + noticeId).apply(cookie))
                .andReturn());
    jdbcTemplate.update(
        "UPDATE pricing_change_notice SET response_due_at = now() - interval '1 hour',"
            + " applies_from_period_start = now() - interval '1 hour',"
            + " effective_at = now() - interval '1 hour', notified_at = now() - interval '40 days'"
            + " WHERE id = ?",
        noticeId);
    assertError(
        mockMvc.perform(
            postRequest(CONSENT_URL, consentBody(preview, noticeId, true)).apply(cookie)),
        ErrorCode.PRICE_CHANGE_NOTICE_CLOSED);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM subscription_purchase_consent", Integer.class))
        .isZero();
  }

  @Test
  void 안내_가격_버전과_다른_가격_버전으로_동의하면_409_CONSENT_TERMS_MISMATCH이다() throws Exception {
    long noticeId = notice();
    Cookie cookie = cookieOf(owner);
    JsonNode preview =
        AuthFlowSupport.json(
            mockMvc
                .perform(getRequest(PREVIEW_URL + "?priceChangeNoticeId=" + noticeId).apply(cookie))
                .andReturn());
    String body = consentBody(preview, noticeId, true).replace("2027-09-STD", "2026-12-STD");
    assertError(
        mockMvc.perform(postRequest(CONSENT_URL, body).apply(cookie)),
        ErrorCode.CONSENT_TERMS_MISMATCH);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT decision FROM pricing_change_notice WHERE id = ?", String.class, noticeId))
        .isEqualTo("PENDING");
  }

  @Test
  void 다른_사무소_안내_ID로는_동의할_수_없다() throws Exception {
    TestData.Staff stranger = owner();
    long strangerSubscription =
        fixtures.subscription(stranger.office().getId()).status("ACTIVE").insert();
    long strangerNotice =
        fixtures
            .notice(stranger.office().getId(), strangerSubscription, fromPrice, toPrice)
            .insert();
    Cookie cookie = cookieOf(owner);
    JsonNode preview =
        AuthFlowSupport.json(
            mockMvc
                .perform(getRequest(PREVIEW_URL + "?priceChangeNoticeId=" + notice()).apply(cookie))
                .andReturn());
    assertError(
        mockMvc.perform(
            postRequest(CONSENT_URL, consentBody(preview, strangerNotice, true)).apply(cookie)),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 안내_목록과_거절은_대표만_할_수_있다() throws Exception {
    assertOwnerOnly(getRequest(LIST_URL));
    assertOwnerOnly(postRequest(LIST_URL + "/1/decline", ""));
  }

  private static String consentBody(JsonNode preview, long noticeId, boolean agreed) {
    return "{\"termsVersion\":\""
        + preview.get("termsVersion").asString()
        + "\",\"plan\":\""
        + preview.get("plan").asString()
        + "\",\"memberCount\":"
        + preview.get("memberCount").asInt()
        + ",\"priceVersion\":\""
        + preview.get("priceVersion").asString()
        + "\",\"priceChangeNoticeId\":"
        + noticeId
        + ",\"totalAmountInclVat\":"
        + preview.get("totalAmountInclVat").asLong()
        + ",\"amountLimit\":"
        + preview.get("amountLimit").asLong()
        + ",\"periodStart\":\""
        + preview.get("periodStart").asString()
        + "\",\"periodEnd\":\""
        + preview.get("periodEnd").asString()
        + "\",\"nextChargeAt\":\""
        + preview.get("nextChargeAt").asString()
        + "\",\"agreed\":"
        + agreed
        + "}";
  }
}
