package com.sairo.be.domain.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.service.AuthSuspensionService;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class AuthSuspensionIntegrationTest extends PaymentTestBase {

  private static final String STATUS_URL = "/api/offices/current/auth-status";

  @Autowired private AuthSuspensionService suspensionService;

  private long trialEvent() {
    return fixtures.trialEvent(
        "GENERAL", "DURATION", daysFromNow(-5), daysFromNow(30), null, "ACTIVE");
  }

  private Map<String, Object> subscription(long id) {
    return jdbcTemplate.queryForMap("SELECT * FROM subscription WHERE id = ?", id);
  }

  @Test
  void 인증_중단이_아니면_인증_상태_조회는_중단_아님이고_값이_없다() throws Exception {
    Ready ready =
        ready(seed -> seed.status("ACTIVE").period(daysFromNow(-5), daysFromNow(25)), true, true);
    mockMvc
        .perform(getRequest(STATUS_URL).apply(cookieOf(ready.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authSuspended").value(false))
        .andExpect(jsonPath("$.suspendedAt").value(nullValue()))
        .andExpect(jsonPath("$.reauthDeadlineAt").value(nullValue()))
        .andExpect(jsonPath("$.trialRemainingSeconds").value(nullValue()));
    mockMvc
        .perform(getRequest(STATUS_URL).apply(cookieOf(owner())))
        .andExpect(jsonPath("$.authSuspended").value(false));
  }

  @Test
  void 중단되면_중단_시각과_30일_재인증_기한과_보존된_체험_잔여_초를_준다() throws Exception {
    long event = trialEvent();
    Ready ready =
        ready(
            seed -> seed.status("TRIAL").trial(event, daysFromNow(-20), daysFromNow(40)),
            true,
            true);
    Instant now = Instant.now();
    suspensionService.suspend(ready.owner().office().getId(), now);
    mockMvc
        .perform(getRequest(STATUS_URL).apply(cookieOf(ready.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authSuspended").value(true))
        .andExpect(jsonPath("$.suspendedAt").exists())
        .andExpect(jsonPath("$.reauthDeadlineAt").exists())
        .andExpect(jsonPath("$.trialRemainingSeconds").isNumber());
    Map<String, Object> sub = subscription(ready.subscriptionId());
    assertThat(sub.get("status")).isEqualTo("SUSPENDED");
    assertThat((Long) sub.get("trial_remaining_seconds")).isBetween(39L * 86_400, 40L * 86_400);
  }

  @Test
  void 고정_종료_방식_체험은_잔여_초를_보존하지_않는다() throws Exception {
    long fixed =
        fixtures.trialEvent(
            "GENERAL", "FIXED_END", daysFromNow(-5), daysFromNow(30), daysFromNow(60), "ACTIVE");
    Ready ready =
        ready(
            seed -> seed.status("TRIAL").trial(fixed, daysFromNow(-20), daysFromNow(60)),
            true,
            true);
    suspensionService.suspend(ready.owner().office().getId(), Instant.now());
    assertThat(subscription(ready.subscriptionId()).get("trial_remaining_seconds")).isNull();
    mockMvc
        .perform(getRequest(STATUS_URL).apply(cookieOf(ready.owner())))
        .andExpect(jsonPath("$.authSuspended").value(true))
        .andExpect(jsonPath("$.trialRemainingSeconds").value(nullValue()));
  }

  @Test
  void 중단하면_미납_청구를_취소하고_잔여_유료기간_환불을_접수하며_동의를_철회한다() throws Exception {
    Instant start = daysFromNow(-10);
    Instant end = daysFromNow(20);
    Ready ready = ready(seed -> seed.status("ACTIVE").period(start, end), true, true);
    long paid =
        fixtures
            .invoice(ready.owner().office().getId(), ready.subscriptionId(), ready.consentId())
            .period(start, end)
            .insert();
    long unpaid =
        fixtures
            .invoice(ready.owner().office().getId(), ready.subscriptionId(), ready.consentId())
            .status("FAILED")
            .period(end, end.plus(30, ChronoUnit.DAYS))
            .insert();
    Instant now = Instant.now();
    suspensionService.suspend(ready.owner().office().getId(), now);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM billing_invoice WHERE id = ?", String.class, unpaid))
        .isEqualTo("CANCELLED");
    Map<String, Object> refund =
        jdbcTemplate.queryForMap(
            "SELECT status, reason_code, reason_event_type, reason_event_id, original_approved_amount,"
                + " total_period_seconds, remaining_period_seconds, entitlement_amount, refund_amount,"
                + " effective_end_at FROM refund_request WHERE invoice_id = ?",
            paid);
    long total = Duration.between(start, end).toSeconds();
    long remaining = (Long) refund.get("remaining_period_seconds");
    assertThat(refund.get("status")).isEqualTo("RECEIVED");
    assertThat(refund.get("reason_code")).isEqualTo("AUTH_SUSPENSION");
    assertThat(refund.get("reason_event_type")).isEqualTo("AUTH_SUSPENSION");
    assertThat(refund.get("reason_event_id")).isNull();
    assertThat(refund.get("original_approved_amount")).isEqualTo(39_900L);
    assertThat(refund.get("total_period_seconds")).isEqualTo(total);
    assertThat(remaining).isBetween(20L * 86_400 - 5, 20L * 86_400);
    assertThat(refund.get("entitlement_amount")).isEqualTo(39_900L * remaining / total);
    assertThat(refund.get("refund_amount")).isEqualTo(39_900L * remaining / total);
    assertThat(refund.get("effective_end_at")).isNotNull();
    List<Map<String, Object>> consents =
        jdbcTemplate.queryForList(
            "SELECT agreed FROM subscription_purchase_consent WHERE subscription_id = ? ORDER BY id",
            ready.subscriptionId());
    assertThat(consents).extracting(row -> row.get("agreed")).containsExactly(true, false);
  }

  @Test
  void 같은_중단을_다시_처리해도_환불을_또_만들지_않고_이미_종료된_구독은_바꾸지_않는다() throws Exception {
    Instant start = daysFromNow(-10);
    Instant end = daysFromNow(20);
    Ready ready = ready(seed -> seed.status("ACTIVE").period(start, end), true, true);
    fixtures
        .invoice(ready.owner().office().getId(), ready.subscriptionId(), ready.consentId())
        .period(start, end)
        .insert();
    suspensionService.suspend(ready.owner().office().getId(), Instant.now());
    suspensionService.suspend(ready.owner().office().getId(), Instant.now());
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM refund_request", Integer.class))
        .isEqualTo(1);

    Ready ended = ready(seed -> seed.status("ENDED").endedAt(daysFromNow(-2)), true, true);
    suspensionService.suspend(ended.owner().office().getId(), Instant.now());
    assertThat(subscription(ended.subscriptionId()).get("status")).isEqualTo("ENDED");
  }

  @Test
  void 재개하면_보존된_체험_잔여_초만큼_새_만료_시각으로_체험을_이어간다() {
    long event = trialEvent();
    Ready ready =
        ready(
            seed -> seed.status("TRIAL").trial(event, daysFromNow(-20), daysFromNow(40)),
            true,
            true);
    Instant suspendedAt = Instant.now();
    suspensionService.suspend(ready.owner().office().getId(), suspendedAt);
    long remaining = (Long) subscription(ready.subscriptionId()).get("trial_remaining_seconds");
    Instant resumeAt = suspendedAt.plus(5, ChronoUnit.DAYS);
    Subscription resumed = suspensionService.resume(ready.owner().office().getId(), resumeAt);
    assertThat(resumed.getStatus().name()).isEqualTo("TRIAL");
    assertThat(resumed.getTrialEndsAt()).isEqualTo(resumeAt.plusSeconds(remaining));
    Map<String, Object> sub = subscription(ready.subscriptionId());
    assertThat(sub.get("suspended_at")).isNull();
    assertThat(sub.get("trial_remaining_seconds")).isNull();
    assertThat(((Timestamp) sub.get("first_billing_scheduled_at")).toInstant())
        .isEqualTo(((Timestamp) sub.get("trial_ends_at")).toInstant());
  }

  @Test
  void 보존한_체험이_없으면_결제_전_상태로_돌아가고_기한이_지나면_재개할_수_없다() {
    Ready ready =
        ready(seed -> seed.status("ACTIVE").period(daysFromNow(-5), daysFromNow(25)), true, true);
    Instant suspendedAt = Instant.now();
    suspensionService.suspend(ready.owner().office().getId(), suspendedAt);
    Subscription resumed =
        suspensionService.resume(ready.owner().office().getId(), suspendedAt.plusSeconds(60));
    assertThat(resumed.getStatus().name()).isEqualTo("PRE_PAYMENT");
    assertThat(resumed.getPrePaymentSince()).isNotNull();
    assertThat(resumed.getSuspendedAt()).isNull();

    Ready late =
        ready(seed -> seed.status("ACTIVE").period(daysFromNow(-5), daysFromNow(25)), true, true);
    suspensionService.suspend(late.owner().office().getId(), suspendedAt);
    assertThatThrownBy(
            () ->
                suspensionService.resume(
                    late.owner().office().getId(),
                    suspendedAt.plus(30, ChronoUnit.DAYS).plusSeconds(1)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_TRANSITION));
  }

  @Test
  void 중단_상태가_아닌_구독의_재개는_INVALID_TRANSITION이고_구독이_없으면_404이다() {
    Ready ready =
        ready(seed -> seed.status("ACTIVE").period(daysFromNow(-5), daysFromNow(25)), true, true);
    assertThatThrownBy(
            () -> suspensionService.resume(ready.owner().office().getId(), Instant.now()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_TRANSITION));
    assertThatThrownBy(() -> suspensionService.resume(owner().office().getId(), Instant.now()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
  }

  @Test
  void 재인증_기한_30일이_지나면_중단_구독을_기한_시각에_종료한다() {
    Ready ready =
        ready(seed -> seed.status("ACTIVE").period(daysFromNow(-5), daysFromNow(25)), true, true);
    Instant suspendedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    suspensionService.suspend(ready.owner().office().getId(), suspendedAt);
    assertThat(
            suspensionService.expireSuspensions(
                suspendedAt.plus(30, ChronoUnit.DAYS).minusSeconds(1)))
        .isZero();
    assertThat(subscription(ready.subscriptionId()).get("status")).isEqualTo("SUSPENDED");
    assertThat(suspensionService.expireSuspensions(suspendedAt.plus(30, ChronoUnit.DAYS)))
        .isEqualTo(1);
    Map<String, Object> sub = subscription(ready.subscriptionId());
    assertThat(sub.get("status")).isEqualTo("ENDED");
    assertThat(((Timestamp) sub.get("ended_at")).toInstant())
        .isEqualTo(suspendedAt.plus(30, ChronoUnit.DAYS));
  }

  @Test
  void 인증_상태_조회는_대표만_할_수_있다() throws Exception {
    assertOwnerOnly(getRequest(STATUS_URL));
  }
}
