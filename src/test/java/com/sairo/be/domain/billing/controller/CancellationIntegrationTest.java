package com.sairo.be.domain.billing.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StepUpState;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@IntegrationTest
class CancellationIntegrationTest extends PaymentTestBase {

  private static final String CANCEL = "/api/subscriptions/cancel";
  private static final String WITHDRAW = "/api/subscriptions/cancel/withdraw";

  private Ready active() {
    return ready(
        seed -> seed.status("ACTIVE").period(daysFromNow(-10), daysFromNow(20)), true, true);
  }

  @Test
  void 해지_예약은_현재_유료기간_종료_시각을_적용_시각으로_기록하고_재인증을_소비한다() throws Exception {
    Ready ready = active();
    Cookie cookie = cookieWithStepUp(ready.owner());
    mockMvc
        .perform(postRequest(CANCEL, "").apply(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.effectiveAt").exists())
        .andExpect(jsonPath("$.revokedAt").value(nullValue()));
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT c.effective_at, c.cancelled_by_membership_id, c.revoked_at, s.current_period_end,"
                + " s.status FROM subscription_cancellation c JOIN subscription s ON s.id = c.subscription_id");
    assertThat(row.get("effective_at")).isEqualTo(row.get("current_period_end"));
    assertThat(row.get("cancelled_by_membership_id")).isEqualTo(ready.owner().membership().getId());
    assertThat(row.get("revoked_at")).isNull();
    assertThat(row.get("status")).isEqualTo("ACTIVE");
    assertThat(sessionAttribute(cookie, StepUpState.STEP_UP_UNTIL_ATTRIBUTE)).isNull();
    mockMvc
        .perform(getRequest("/api/subscriptions/current").apply(cookieOf(ready.owner())))
        .andExpect(jsonPath("$.cancellationEffectiveAt").exists())
        .andExpect(jsonPath("$.nextChargeAt").value(nullValue()));
  }

  @Test
  void 해지_예약은_재인증이_없으면_401_STEP_UP_REQUIRED이다() throws Exception {
    Ready ready = active();
    assertError(
        mockMvc.perform(postRequest(CANCEL, "").apply(cookieOf(ready.owner()))),
        ErrorCode.STEP_UP_REQUIRED);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM subscription_cancellation", Integer.class))
        .isZero();
  }

  @Test
  void 이미_해지가_예약됐거나_종료_인증중단_상태의_해지는_409_INVALID_TRANSITION이다() throws Exception {
    Ready ready = active();
    mockMvc
        .perform(postRequest(CANCEL, "").apply(cookieWithStepUp(ready.owner())))
        .andExpect(status().isOk());
    assertError(
        mockMvc.perform(postRequest(CANCEL, "").apply(cookieWithStepUp(ready.owner()))),
        ErrorCode.INVALID_TRANSITION);
    Ready ended = ready(seed -> seed.status("ENDED").endedAt(daysFromNow(-2)), true, true);
    assertError(
        mockMvc.perform(postRequest(CANCEL, "").apply(cookieWithStepUp(ended.owner()))),
        ErrorCode.INVALID_TRANSITION);
    Ready suspended = ready(seed -> seed.status("SUSPENDED"), true, true);
    assertError(
        mockMvc.perform(postRequest(CANCEL, "").apply(cookieWithStepUp(suspended.owner()))),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 해지_예약을_철회하면_예약이_사라지고_다시_해지할_수_있다() throws Exception {
    Ready ready = active();
    mockMvc
        .perform(postRequest(CANCEL, "").apply(cookieWithStepUp(ready.owner())))
        .andExpect(status().isOk());
    mockMvc
        .perform(postRequest(WITHDRAW, "").apply(cookieOf(ready.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.withdrawn").value(true))
        .andExpect(jsonPath("$.revokedAt").exists());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT revoked_at FROM subscription_cancellation", Timestamp.class))
        .isNotNull();
    mockMvc
        .perform(getRequest("/api/subscriptions/current").apply(cookieOf(ready.owner())))
        .andExpect(jsonPath("$.cancellationEffectiveAt").value(nullValue()))
        .andExpect(jsonPath("$.nextChargeAt").exists());
    mockMvc
        .perform(postRequest(CANCEL, "").apply(cookieWithStepUp(ready.owner())))
        .andExpect(status().isOk());
  }

  @Test
  void 활성_해지_예약이_없으면_철회는_409_INVALID_TRANSITION이다() throws Exception {
    Ready ready = active();
    assertError(
        mockMvc.perform(postRequest(WITHDRAW, "").apply(cookieOf(ready.owner()))),
        ErrorCode.INVALID_TRANSITION);
    mockMvc
        .perform(postRequest(CANCEL, "").apply(cookieWithStepUp(ready.owner())))
        .andExpect(status().isOk());
    mockMvc
        .perform(postRequest(WITHDRAW, "").apply(cookieOf(ready.owner())))
        .andExpect(status().isOk());
    assertError(
        mockMvc.perform(postRequest(WITHDRAW, "").apply(cookieOf(ready.owner()))),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 이미_발효된_해지_예약의_철회는_409_CANCELLATION_ALREADY_EFFECTIVE이다() throws Exception {
    Ready ready = active();
    jdbcTemplate.update(
        "INSERT INTO subscription_cancellation (subscription_id, requested_at, effective_at,"
            + " cancelled_by_membership_id) VALUES (?, ?, ?, ?)",
        ready.subscriptionId(),
        Timestamp.from(Instant.now().minusSeconds(86_400L * 30)),
        Timestamp.from(Instant.now().minusSeconds(60)),
        ready.owner().membership().getId());
    assertError(
        mockMvc.perform(postRequest(WITHDRAW, "").apply(cookieOf(ready.owner()))),
        ErrorCode.CANCELLATION_ALREADY_EFFECTIVE);
  }

  @Test
  void 체험_중_해지는_체험_종료_시각에_적용된다() throws Exception {
    long event =
        fixtures.trialEvent(
            "GENERAL", "DURATION", daysFromNow(-5), daysFromNow(30), null, "ACTIVE");
    Instant trialEnd = daysFromNow(40);
    Ready ready =
        ready(seed -> seed.status("TRIAL").trial(event, daysFromNow(-20), trialEnd), true, true);
    mockMvc
        .perform(postRequest(CANCEL, "").apply(cookieWithStepUp(ready.owner())))
        .andExpect(status().isOk());
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT c.effective_at, s.trial_ends_at, s.status FROM subscription_cancellation c JOIN"
                + " subscription s ON s.id = c.subscription_id");
    assertThat(row.get("effective_at")).isEqualTo(row.get("trial_ends_at"));
    assertThat(row.get("status")).isEqualTo("TRIAL");
  }

  @Test
  void 미납_중_해지는_미승인_청구를_취소하고_즉시_종료하며_동의_철회_사건을_남긴다() throws Exception {
    Ready ready =
        ready(
            seed ->
                seed.status("PAST_DUE_GRACE")
                    .period(daysFromNow(-31), daysFromNow(-1))
                    .pastDueSince(daysFromNow(-1)),
            true,
            true);
    long invoiceId =
        fixtures
            .invoice(ready.owner().office().getId(), ready.subscriptionId(), ready.consentId())
            .status("FAILED")
            .period(daysFromNow(-1), daysFromNow(29))
            .insert();
    mockMvc
        .perform(postRequest(CANCEL, "").apply(cookieWithStepUp(ready.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.effectiveAt").exists());
    Map<String, Object> subscription =
        jdbcTemplate.queryForMap(
            "SELECT status, ended_at, past_due_since FROM subscription WHERE id = ?",
            ready.subscriptionId());
    assertThat(subscription.get("status")).isEqualTo("ENDED");
    assertThat(subscription.get("ended_at")).isNotNull();
    assertThat(subscription.get("past_due_since")).isNull();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM billing_invoice WHERE id = ?", String.class, invoiceId))
        .isEqualTo("CANCELLED");
    List<Map<String, Object>> consents =
        jdbcTemplate.queryForList(
            "SELECT agreed FROM subscription_purchase_consent WHERE subscription_id = ? ORDER BY id",
            ready.subscriptionId());
    assertThat(consents).extracting(row -> row.get("agreed")).containsExactly(true, false);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM terms_agreement WHERE consent_path = 'PURCHASE_CONSENT' AND"
                    + " agreed = false",
                Integer.class))
        .isEqualTo(1);
    assertError(
        mockMvc.perform(postRequest(WITHDRAW, "").apply(cookieOf(ready.owner()))),
        ErrorCode.CANCELLATION_ALREADY_EFFECTIVE);
  }

  @Test
  void 결제_전_상태의_해지는_즉시_종료된다() throws Exception {
    Ready ready =
        ready(seed -> seed.status("PRE_PAYMENT").prePaymentSince(daysFromNow(-3)), false, true);
    mockMvc
        .perform(postRequest(CANCEL, "").apply(cookieWithStepUp(ready.owner())))
        .andExpect(status().isOk());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM subscription WHERE id = ?",
                String.class,
                ready.subscriptionId()))
        .isEqualTo("ENDED");
  }

  @Test
  void 구독이_없는_사무소의_해지는_404이다() throws Exception {
    var owner = owner();
    assertError(
        mockMvc.perform(postRequest(CANCEL, "").apply(cookieWithStepUp(owner))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(postRequest(WITHDRAW, "").apply(cookieOf(owner))),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 해지와_철회는_대표만_할_수_있다() throws Exception {
    assertOwnerOnly(postRequest(CANCEL, ""));
    assertOwnerOnly(postRequest(WITHDRAW, ""));
  }
}
