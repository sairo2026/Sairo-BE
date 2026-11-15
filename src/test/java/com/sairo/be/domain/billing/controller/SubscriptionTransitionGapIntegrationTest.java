package com.sairo.be.domain.billing.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.billing.entity.BillingInvoice;
import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.repository.BillingInvoiceRepository;
import com.sairo.be.domain.billing.repository.SubscriptionRepository;
import com.sairo.be.domain.billing.service.AuthSuspensionService;
import com.sairo.be.domain.billing.service.BillingJobService;
import com.sairo.be.domain.billing.service.SubscriptionTransitions;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.PaymentGateway.PaymentStatus;
import com.sairo.be.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class SubscriptionTransitionGapIntegrationTest extends PaymentTestBase {

  private static final String FIRST = "/api/subscriptions/first-payment";
  private static final String RETRY = "/api/subscriptions/retry-payment";
  private static final String CANCEL = "/api/subscriptions/cancel";
  private static final String WITHDRAW = "/api/subscriptions/cancel/withdraw";

  @Autowired private BillingJobService jobService;
  @Autowired private AuthSuspensionService suspensionService;
  @Autowired private SubscriptionTransitions transitions;
  @Autowired private SubscriptionRepository subscriptionRepository;
  @Autowired private BillingInvoiceRepository invoiceRepository;
  @Autowired private TransactionTemplate transactionTemplate;

  private Map<String, Object> subscription(long id) {
    return jdbcTemplate.queryForMap("SELECT * FROM subscription WHERE id = ?", id);
  }

  private long noticeCount(long userId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM notification WHERE recipient_user_id = ?", Long.class, userId);
  }

  private List<String> noticeTypes(long userId) {
    return jdbcTemplate.queryForList(
        "SELECT notification_type FROM notification WHERE recipient_user_id = ? ORDER BY id",
        String.class,
        userId);
  }

  private List<String> noticeMessages(long userId, String type) {
    return jdbcTemplate.queryForList(
        "SELECT message FROM notification WHERE recipient_user_id = ? AND notification_type = ?"
            + " ORDER BY id",
        String.class,
        userId,
        type);
  }

  private List<String> emailChannels(long userId, String type) {
    return jdbcTemplate.queryForList(
        "SELECT d.channel FROM notification_delivery d JOIN notification n ON n.id ="
            + " d.notification_id WHERE n.recipient_user_id = ? AND n.notification_type = ?",
        String.class,
        userId,
        type);
  }

  private void approvedRegistration(Ready ready) {
    jdbcTemplate.update(
        "INSERT INTO office_registration (applicant_user_id, status, entity_type,"
            + " registration_authority, real_estate_license_number,"
            + " business_registration_number, requested_office_name,"
            + " requested_representative_name, requested_representative_phone, requested_address,"
            + " applicant_registration_relation, reviewed_at, resulting_office_id) VALUES (?,"
            + " 'APPROVED', 'INDIVIDUAL', '서울', ?, '1234567890', '사무소', '홍대표',"
            + " '010-0000-0000', '서울', '대표', now(), ?)",
        ready.owner().user().getId(),
        "LIC-" + ready.owner().office().getId(),
        ready.owner().office().getId());
  }

  private Ready trialDue() {
    long event =
        fixtures.trialEvent(
            "GENERAL", "DURATION", daysFromNow(-90), daysFromNow(30), null, "ACTIVE");
    return ready(
        seed -> seed.status("TRIAL").trial(event, daysFromNow(-61), daysFromNow(-1)), true, true);
  }

  private Ready active() {
    return ready(
        seed -> seed.status("ACTIVE").period(daysFromNow(-5), daysFromNow(25)), true, true);
  }

  @Test
  void 첫_결제가_확정_실패해_체험이_끝나면_구독은_종료되고_대표에게_결제_알림을_한_번만_보낸다() {
    Ready ready = trialDue();
    paymentGateway.respondWith(PaymentStatus.FAILED);
    jobService.runFirstCharges(Instant.now());
    Map<String, Object> sub = subscription(ready.subscriptionId());
    assertThat(sub.get("status")).isEqualTo("ENDED");
    assertThat(sub.get("ended_at")).isNotNull();
    long userId = ready.owner().user().getId();
    assertThat(noticeTypes(userId)).containsExactly("PAYMENT");
    assertThat(noticeMessages(userId, "PAYMENT").getFirst())
        .contains("결제가 완료되지 않아 체험이 종료되었습니다")
        .contains("다시 결제할 수 있습니다");
    assertThat(emailChannels(userId, "PAYMENT")).containsExactly("EMAIL");
    jobService.runFirstCharges(Instant.now());
    assertThat(noticeCount(userId)).isEqualTo(1L);
  }

  @Test
  void 갱신_시점에_해지_예약이_발효되면_종료하고_동의를_철회하며_청구와_알림이_없다() {
    Ready ready =
        ready(seed -> seed.status("ACTIVE").period(daysFromNow(-31), daysFromNow(-1)), true, true);
    jdbcTemplate.update(
        "INSERT INTO subscription_cancellation (subscription_id, requested_at, effective_at,"
            + " cancelled_by_membership_id) VALUES (?, ?, ?, ?)",
        ready.subscriptionId(),
        Timestamp.from(daysFromNow(-20)),
        Timestamp.from(daysFromNow(-1)),
        ready.owner().membership().getId());
    jobService.runRenewals(Instant.now());
    assertThat(subscription(ready.subscriptionId()).get("status")).isEqualTo("ENDED");
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT agreed FROM subscription_purchase_consent WHERE subscription_id = ?"
                    + " ORDER BY id",
                Boolean.class,
                ready.subscriptionId()))
        .containsExactly(true, false);
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing_invoice", Long.class))
        .isZero();
    assertThat(paymentGateway.charges()).isEmpty();
    assertThat(noticeCount(ready.owner().user().getId())).isZero();
  }

  @Test
  void 해지_예약은_구독_변경_안내를_남기고_철회는_취소_안내를_한_번_남기며_예약을_거둔다() throws Exception {
    Ready ready = active();
    mockMvc
        .perform(postRequest(CANCEL, "").apply(cookieWithStepUp(ready.owner())))
        .andExpect(status().isOk());
    long userId = ready.owner().user().getId();
    assertThat(noticeTypes(userId)).containsExactly("PLAN_CHANGED");
    mockMvc
        .perform(postRequest(WITHDRAW, "").apply(cookieOf(ready.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.withdrawn").value(true));
    assertThat(noticeTypes(userId)).containsExactly("PLAN_CHANGED", "PLAN_CHANGED");
    assertThat(noticeMessages(userId, "PLAN_CHANGED").getLast()).contains("해지 예약이 취소되었습니다");
    assertThat(emailChannels(userId, "PLAN_CHANGED")).containsExactly("EMAIL", "EMAIL");
    mockMvc
        .perform(postRequest(WITHDRAW, "").apply(cookieOf(ready.owner())))
        .andExpect(status().isConflict());
    assertThat(noticeTypes(userId)).hasSize(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT revoked_at IS NOT NULL FROM subscription_cancellation WHERE subscription_id"
                    + " = ?",
                Boolean.class,
                ready.subscriptionId()))
        .isTrue();
  }

  @Test
  void 인증_중단_재개와_중단_만료_종료는_대표에게_사무소_인증_알림을_한_번씩_남긴다() {
    Ready resumed = active();
    approvedRegistration(resumed);
    Instant suspendedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    suspensionService.suspend(resumed.owner().office().getId(), suspendedAt);
    suspensionService.resume(resumed.owner().office().getId(), suspendedAt.plusSeconds(60));
    assertThat(subscription(resumed.subscriptionId()).get("status")).isEqualTo("PRE_PAYMENT");
    long resumedUser = resumed.owner().user().getId();
    assertThat(noticeTypes(resumedUser)).containsExactly("OFFICE_REVIEW");
    assertThat(noticeMessages(resumedUser, "OFFICE_REVIEW").getFirst()).contains("인증 중단이 해제되었습니다");
    assertThat(emailChannels(resumedUser, "OFFICE_REVIEW")).containsExactly("EMAIL");

    Ready expired = active();
    approvedRegistration(expired);
    suspensionService.suspend(expired.owner().office().getId(), suspendedAt);
    assertThat(suspensionService.expireSuspensions(suspendedAt.plusSeconds(30L * 86_400)))
        .isEqualTo(1);
    assertThat(subscription(expired.subscriptionId()).get("status")).isEqualTo("ENDED");
    long expiredUser = expired.owner().user().getId();
    assertThat(noticeTypes(expiredUser)).containsExactly("OFFICE_REVIEW");
    assertThat(noticeMessages(expiredUser, "OFFICE_REVIEW").getFirst())
        .contains("구독이 종료되었습니다")
        .contains("30일 동안");
    assertThat(suspensionService.expireSuspensions(suspendedAt.plusSeconds(31L * 86_400))).isZero();
    assertThat(noticeCount(expiredUser)).isEqualTo(1L);
  }

  @Test
  void 중단_상태에서_결제가_성공으로_확인돼도_구독은_중단을_유지하고_기간을_바꾸지_않는다() {
    Ready ready = active();
    long invoiceId =
        fixtures
            .invoice(ready.owner().office().getId(), ready.subscriptionId(), ready.consentId())
            .status("PAID")
            .period(daysFromNow(25), daysFromNow(55))
            .insert();
    suspensionService.suspend(ready.owner().office().getId(), Instant.now());
    Map<String, Object> before = subscription(ready.subscriptionId());
    transactionTemplate.executeWithoutResult(
        tx -> {
          Subscription subscription =
              subscriptionRepository.findById(ready.subscriptionId()).orElseThrow();
          BillingInvoice invoice = invoiceRepository.findById(invoiceId).orElseThrow();
          transitions.onPaid(subscription, invoice);
          subscriptionRepository.saveAndFlush(subscription);
        });
    Map<String, Object> after = subscription(ready.subscriptionId());
    assertThat(after.get("status")).isEqualTo("SUSPENDED");
    assertThat(after.get("current_period_start")).isEqualTo(before.get("current_period_start"));
    assertThat(after.get("current_period_end")).isEqualTo(before.get("current_period_end"));
    assertThat(after.get("suspended_at")).isNotNull();
  }

  @Test
  void 인증_중단_상태의_첫_결제와_재결제_요청은_409_INVALID_TRANSITION이고_결제대행사를_부르지_않는다() throws Exception {
    Ready ready = active();
    suspensionService.suspend(ready.owner().office().getId(), Instant.now());
    assertError(
        mockMvc.perform(postRequest(FIRST, "").apply(cookieOf(ready.owner()))),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        mockMvc.perform(postRequest(RETRY, "").apply(cookieOf(ready.owner()))),
        ErrorCode.INVALID_TRANSITION);
    assertThat(paymentGateway.charges()).isEmpty();
    assertThat(subscription(ready.subscriptionId()).get("status")).isEqualTo("SUSPENDED");
  }

  @Test
  void 미납_제한_상태에서_대표가_직접_재결제하면_원청구_기간을_유지하고_구독중으로_돌아온다() throws Exception {
    Instant d0 = daysFromNow(-9);
    Ready ready =
        ready(
            seed ->
                seed.status("PAST_DUE_RESTRICTED")
                    .period(d0.minusSeconds(30L * 86_400), d0)
                    .pastDueSince(d0),
            true,
            true);
    long invoiceId =
        fixtures
            .invoice(ready.owner().office().getId(), ready.subscriptionId(), ready.consentId())
            .status("FAILED")
            .period(d0, d0.plusSeconds(30L * 86_400))
            .insert();
    Map<String, Object> invoiceBefore =
        jdbcTemplate.queryForMap(
            "SELECT billing_period_start, billing_period_end FROM billing_invoice WHERE id = ?",
            invoiceId);
    mockMvc
        .perform(postRequest(RETRY, "").apply(cookieOf(ready.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.outstandingAmount").value(0));
    Map<String, Object> sub = subscription(ready.subscriptionId());
    assertThat(sub.get("status")).isEqualTo("ACTIVE");
    assertThat(sub.get("past_due_since")).isNull();
    assertThat(sub.get("current_period_start"))
        .isEqualTo(invoiceBefore.get("billing_period_start"));
    assertThat(sub.get("current_period_end")).isEqualTo(invoiceBefore.get("billing_period_end"));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM billing_invoice WHERE id = ?", String.class, invoiceId))
        .isEqualTo("PAID");
    assertThat(paymentGateway.charges()).hasSize(1);
  }
}
