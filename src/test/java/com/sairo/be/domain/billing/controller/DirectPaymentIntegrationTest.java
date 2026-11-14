package com.sairo.be.domain.billing.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.PaymentGateway.ChargeCommand;
import com.sairo.be.global.port.PaymentGateway.PaymentStatus;
import com.sairo.be.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

@IntegrationTest
class DirectPaymentIntegrationTest extends PaymentTestBase {

  private static final String FIRST = "/api/subscriptions/first-payment";
  private static final String RETRY = "/api/subscriptions/retry-payment";
  private static final Pattern UUID_V4 =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

  private Ready preparing() {
    return ready(seed -> seed.status("PRE_PAYMENT").prePaymentSince(daysFromNow(-2)), true, true);
  }

  private long count(String table) {
    return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
  }

  @Test
  void 첫_결제가_성공하면_청구서와_시도를_저장하고_구독을_구독중으로_바꾼다() throws Exception {
    Ready ready = preparing();
    mockMvc
        .perform(postRequest(FIRST, "").apply(cookieOf(ready.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.plan").value("SOLO"))
        .andExpect(jsonPath("$.currentPeriodEnd").exists())
        .andExpect(jsonPath("$.pastDueSince").value(nullValue()))
        .andExpect(jsonPath("$.outstandingAmount").value(0))
        .andExpect(jsonPath("$.isInReturnPeriod").value(false))
        .andExpect(jsonPath("$.subscriptionId").value(ready.subscriptionId()))
        .andExpect(jsonPath("$.officeId").value(ready.owner().office().getId()));
    Map<String, Object> invoice =
        jdbcTemplate.queryForMap(
            "SELECT status, amount, supply_amount, vat_amount, vat_rate_bp_snapshot, member_count_snapshot,"
                + " price_version, billing_period_start, billing_period_end, paid_at FROM billing_invoice");
    assertThat(invoice)
        .containsEntry("status", "PAID")
        .containsEntry("amount", 39_900L)
        .containsEntry("supply_amount", 36_273L)
        .containsEntry("vat_amount", 3_627L)
        .containsEntry("vat_rate_bp_snapshot", 1_000)
        .containsEntry("member_count_snapshot", 1)
        .containsEntry("price_version", "2026-12-STD");
    assertThat(invoice.get("paid_at")).isNotNull();
    Map<String, Object> attempt =
        jdbcTemplate.queryForMap(
            "SELECT attempt_no, result, pg_order_id, idempotency_key, pg_transaction_id FROM billing_charge_attempt");
    assertThat(attempt).containsEntry("attempt_no", 1).containsEntry("result", "SUCCESS");
    assertThat((String) attempt.get("pg_order_id")).matches("^[A-Za-z0-9_-]{6,64}$");
    assertThat((String) attempt.get("idempotency_key")).matches(UUID_V4);
    assertThat(attempt.get("pg_transaction_id"))
        .isEqualTo("stub-payment-" + attempt.get("pg_order_id"));
    Map<String, Object> subscription =
        jdbcTemplate.queryForMap(
            "SELECT status, current_period_start, current_period_end, cycle_anchor_at, pre_payment_since,"
                + " version FROM subscription WHERE id = ?",
            ready.subscriptionId());
    assertThat(subscription.get("status")).isEqualTo("ACTIVE");
    assertThat(subscription.get("current_period_start"))
        .isEqualTo(invoice.get("billing_period_start"));
    assertThat(subscription.get("current_period_end")).isEqualTo(invoice.get("billing_period_end"));
    assertThat(subscription.get("cycle_anchor_at")).isEqualTo(invoice.get("billing_period_start"));
    assertThat(subscription.get("pre_payment_since")).isNull();
    assertThat((Long) subscription.get("version")).isGreaterThan(1L);
  }

  @Test
  void 결제대행사에는_복호화한_빌링키와_고객_키와_금액과_주문_ID와_멱등키를_보낸다() throws Exception {
    Ready ready = preparing();
    mockMvc
        .perform(postRequest(FIRST, "").apply(cookieOf(ready.owner())))
        .andExpect(status().isOk());
    List<ChargeCommand> charges = paymentGateway.charges();
    assertThat(charges).hasSize(1);
    ChargeCommand command = charges.get(0);
    assertThat(command.billingKey()).isEqualTo(BILLING_KEY);
    assertThat(command.customerKey()).isEqualTo("customer-key-" + ready.owner().office().getId());
    assertThat(command.amount()).isEqualTo(39_900L);
    assertThat(command.orderId()).matches("^[A-Za-z0-9_-]{6,64}$");
    assertThat(command.idempotencyKey()).matches(UUID_V4);
  }

  @Test
  void 유효한_동의가_없거나_카드가_없거나_한도를_넘으면_402_PAYMENT_METHOD_REQUIRED이고_결제대행사를_호출하지_않는다()
      throws Exception {
    Ready noConsent =
        ready(seed -> seed.status("PRE_PAYMENT").prePaymentSince(daysFromNow(-1)), false, true);
    assertError(
        mockMvc.perform(postRequest(FIRST, "").apply(cookieOf(noConsent.owner()))),
        ErrorCode.PAYMENT_METHOD_REQUIRED);
    Ready noKey =
        ready(seed -> seed.status("PRE_PAYMENT").prePaymentSince(daysFromNow(-1)), true, false);
    assertError(
        mockMvc.perform(postRequest(FIRST, "").apply(cookieOf(noKey.owner()))),
        ErrorCode.PAYMENT_METHOD_REQUIRED);
    Ready lowLimit =
        ready(seed -> seed.status("PRE_PAYMENT").prePaymentSince(daysFromNow(-1)), false, true);
    fixtures.consent(
        lowLimit.subscriptionId(),
        lowLimit.owner().user().getId(),
        lowLimit.owner().office().getId(),
        29_900,
        2_718,
        29_900);
    assertError(
        mockMvc.perform(postRequest(FIRST, "").apply(cookieOf(lowLimit.owner()))),
        ErrorCode.PAYMENT_METHOD_REQUIRED);
    assertThat(paymentGateway.charges()).isEmpty();
    assertThat(count("billing_invoice")).isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM subscription WHERE id = ?",
                String.class,
                noConsent.subscriptionId()))
        .isEqualTo("PRE_PAYMENT");
  }

  @Test
  void 철회된_동의는_유효하지_않다() throws Exception {
    Ready ready = preparing();
    long agreement =
        jdbcTemplate.queryForObject(
            "INSERT INTO terms_agreement (user_id, terms_code, terms_version, agreed, consent_path,"
                + " office_id) VALUES (?, 'SUBSCRIPTION_PURCHASE', '2026-12-01', false,"
                + " 'PURCHASE_CONSENT', ?) RETURNING id",
            Long.class,
            ready.owner().user().getId(),
            ready.owner().office().getId());
    jdbcTemplate.update(
        "INSERT INTO subscription_purchase_consent (subscription_id, user_id, terms_agreement_id, plan,"
            + " member_count, total_amount_incl_vat, vat_amount, amount_limit, period_start,"
            + " period_end, next_charge_at, agreed) VALUES (?, ?, ?, 'SOLO', 1, 39900, 3627, 39900,"
            + " now(), now() + interval '30 days', now() + interval '30 days', false)",
        ready.subscriptionId(),
        ready.owner().user().getId(),
        agreement);
    assertError(
        mockMvc.perform(postRequest(FIRST, "").apply(cookieOf(ready.owner()))),
        ErrorCode.PAYMENT_METHOD_REQUIRED);
  }

  @Test
  void 결제가_확정_실패하면_402_PAYMENT_FAILED이고_구독은_결제_전을_유지하며_다음_요청은_같은_청구서의_새_시도다() throws Exception {
    Ready ready = preparing();
    paymentGateway.respondWith(PaymentStatus.FAILED);
    assertError(
        mockMvc.perform(postRequest(FIRST, "").apply(cookieOf(ready.owner()))),
        ErrorCode.PAYMENT_FAILED);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM subscription WHERE id = ?",
                String.class,
                ready.subscriptionId()))
        .isEqualTo("PRE_PAYMENT");
    Map<String, Object> failed =
        jdbcTemplate.queryForMap(
            "SELECT i.status, a.result, a.failure_category FROM billing_invoice i JOIN"
                + " billing_charge_attempt a ON a.invoice_id = i.id");
    assertThat(failed)
        .containsEntry("status", "FAILED")
        .containsEntry("result", "FAILED")
        .containsEntry("failure_category", "OTHER");
    paymentGateway.respondWith(PaymentStatus.SUCCEEDED);
    mockMvc
        .perform(postRequest(FIRST, "").apply(cookieOf(ready.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    assertThat(count("billing_invoice")).isEqualTo(1);
    List<Map<String, Object>> attempts =
        jdbcTemplate.queryForList(
            "SELECT attempt_no, pg_order_id, idempotency_key FROM billing_charge_attempt ORDER BY attempt_no");
    assertThat(attempts).hasSize(2);
    assertThat(attempts.get(1).get("attempt_no")).isEqualTo(2);
    assertThat(attempts.get(1).get("pg_order_id")).isNotEqualTo(attempts.get(0).get("pg_order_id"));
    assertThat(attempts.get(1).get("idempotency_key"))
        .isNotEqualTo(attempts.get(0).get("idempotency_key"));
  }

  @Test
  void 결과가_확인_중이면_구독을_유지하고_다시_요청하면_409_PAYMENT_VERIFYING이며_새_결제를_만들지_않는다() throws Exception {
    Ready ready = preparing();
    paymentGateway.respondWith(PaymentStatus.VERIFYING);
    mockMvc
        .perform(postRequest(FIRST, "").apply(cookieOf(ready.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PRE_PAYMENT"));
    assertThat(jdbcTemplate.queryForObject("SELECT status FROM billing_invoice", String.class))
        .isEqualTo("VERIFYING");
    assertThat(
            jdbcTemplate.queryForObject("SELECT result FROM billing_charge_attempt", String.class))
        .isEqualTo("VERIFYING");
    assertError(
        mockMvc.perform(postRequest(FIRST, "").apply(cookieOf(ready.owner()))),
        ErrorCode.PAYMENT_VERIFYING);
    assertThat(paymentGateway.charges()).hasSize(1);
    assertThat(count("billing_charge_attempt")).isEqualTo(1);
  }

  @Test
  void 결제대행사_호출이_예외로_끝나면_확인_중으로_보류한다() throws Exception {
    Ready ready = preparing();
    paymentGateway.throwOnNextCharge(new IllegalStateException("timeout"));
    mockMvc
        .perform(postRequest(FIRST, "").apply(cookieOf(ready.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PRE_PAYMENT"));
    assertThat(jdbcTemplate.queryForObject("SELECT status FROM billing_invoice", String.class))
        .isEqualTo("VERIFYING");
    assertError(
        mockMvc.perform(postRequest(FIRST, "").apply(cookieOf(ready.owner()))),
        ErrorCode.PAYMENT_VERIFYING);
  }

  @Test
  void 결제_전이_아닌_상태의_첫_결제는_409_INVALID_TRANSITION이다() throws Exception {
    for (String state : new String[] {"ACTIVE", "ENDED"}) {
      Ready ready =
          ready(
              seed ->
                  seed.status(state)
                      .period(daysFromNow(-5), daysFromNow(25))
                      .endedAt("ENDED".equals(state) ? daysFromNow(-1) : null),
              true,
              true);
      assertError(
          mockMvc.perform(postRequest(FIRST, "").apply(cookieOf(ready.owner()))),
          ErrorCode.INVALID_TRANSITION);
    }
    assertThat(paymentGateway.charges()).isEmpty();
  }

  @Test
  void 미납_유예_상태의_재결제는_원청구_기간을_유지하고_구독중으로_돌린다() throws Exception {
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
    Map<String, Object> before =
        jdbcTemplate.queryForMap(
            "SELECT billing_period_start, billing_period_end FROM billing_invoice WHERE id = ?",
            invoiceId);
    mockMvc
        .perform(postRequest(RETRY, "").apply(cookieOf(ready.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.pastDueSince").value(nullValue()))
        .andExpect(jsonPath("$.outstandingAmount").value(0));
    Map<String, Object> subscription =
        jdbcTemplate.queryForMap(
            "SELECT current_period_start, current_period_end FROM subscription WHERE id = ?",
            ready.subscriptionId());
    assertThat(subscription.get("current_period_start"))
        .isEqualTo(before.get("billing_period_start"));
    assertThat(subscription.get("current_period_end")).isEqualTo(before.get("billing_period_end"));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM billing_invoice WHERE id = ?", String.class, invoiceId))
        .isEqualTo("PAID");
    assertThat(paymentGateway.charges().get(0).amount()).isEqualTo(39_900L);
  }

  @Test
  void 반환기간_중_종료_구독의_재결제는_새_청구서로_구독을_다시_구독중으로_만든다() throws Exception {
    Ready ready = ready(seed -> seed.status("ENDED").endedAt(daysFromNow(-3)), true, true);
    mockMvc
        .perform(postRequest(RETRY, "").apply(cookieOf(ready.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.isInReturnPeriod").value(false))
        .andExpect(jsonPath("$.returnPeriodEndsAt").value(nullValue()));
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT status, ended_at, cycle_anchor_at, current_period_end FROM subscription WHERE id = ?",
            ready.subscriptionId());
    assertThat(row.get("status")).isEqualTo("ACTIVE");
    assertThat(row.get("ended_at")).isNull();
    assertThat(row.get("cycle_anchor_at")).isNotNull();
    assertThat(count("billing_invoice")).isEqualTo(1);
  }

  @Test
  void 재결제는_구독중_체험_반환기간_이후에는_409_INVALID_TRANSITION이다() throws Exception {
    Ready active =
        ready(seed -> seed.status("ACTIVE").period(daysFromNow(-5), daysFromNow(25)), true, true);
    assertError(
        mockMvc.perform(postRequest(RETRY, "").apply(cookieOf(active.owner()))),
        ErrorCode.INVALID_TRANSITION);
    Ready expired = ready(seed -> seed.status("ENDED").endedAt(daysFromNow(-31)), true, true);
    assertError(
        mockMvc.perform(postRequest(RETRY, "").apply(cookieOf(expired.owner()))),
        ErrorCode.INVALID_TRANSITION);
    Ready preparing = preparing();
    assertError(
        mockMvc.perform(postRequest(RETRY, "").apply(cookieOf(preparing.owner()))),
        ErrorCode.INVALID_TRANSITION);
    assertThat(paymentGateway.charges()).isEmpty();
  }

  @Test
  void 재결제는_동의나_카드가_없으면_402_PAYMENT_METHOD_REQUIRED이고_실패하면_402_PAYMENT_FAILED로_상태를_유지한다()
      throws Exception {
    Ready noKey = ready(seed -> seed.status("ENDED").endedAt(daysFromNow(-3)), true, false);
    assertError(
        mockMvc.perform(postRequest(RETRY, "").apply(cookieOf(noKey.owner()))),
        ErrorCode.PAYMENT_METHOD_REQUIRED);
    Ready failing = ready(seed -> seed.status("ENDED").endedAt(daysFromNow(-3)), true, true);
    paymentGateway.respondWith(PaymentStatus.FAILED);
    assertError(
        mockMvc.perform(postRequest(RETRY, "").apply(cookieOf(failing.owner()))),
        ErrorCode.PAYMENT_FAILED);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM subscription WHERE id = ?",
                String.class,
                failing.subscriptionId()))
        .isEqualTo("ENDED");
  }

  @Test
  void 처리_중이거나_확인_중인_결제가_있으면_재결제는_409_PAYMENT_VERIFYING이다() throws Exception {
    Ready ready = ready(seed -> seed.status("ENDED").endedAt(daysFromNow(-3)), true, true);
    paymentGateway.respondWith(PaymentStatus.VERIFYING);
    mockMvc
        .perform(postRequest(RETRY, "").apply(cookieOf(ready.owner())))
        .andExpect(status().isOk());
    assertError(
        mockMvc.perform(postRequest(RETRY, "").apply(cookieOf(ready.owner()))),
        ErrorCode.PAYMENT_VERIFYING);
    assertThat(paymentGateway.charges()).hasSize(1);
  }

  @Test
  void 결제_요청은_대표만_할_수_있다() throws Exception {
    assertOwnerOnly(postRequest(FIRST, ""));
    assertOwnerOnly(postRequest(RETRY, ""));
  }
}
