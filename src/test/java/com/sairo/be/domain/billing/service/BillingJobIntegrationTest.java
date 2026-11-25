package com.sairo.be.domain.billing.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.global.port.PaymentGateway.PaymentStatus;
import com.sairo.be.global.port.stub.StubPaymentGateway;
import com.sairo.be.global.support.TextCipher;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.AppUserBuilder;
import com.sairo.be.support.fixture.BillingFixtures;
import com.sairo.be.support.fixture.BillingFixtures.SubscriptionSeed;
import com.sairo.be.support.fixture.TestData;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

@IntegrationTest
class BillingJobIntegrationTest extends AuthIntegrationTestBase {

  @Autowired private BillingJobService jobService;
  @Autowired private BillingChargeService chargeService;
  @Autowired private SystemJobRunService jobRunService;
  @Autowired private StubPaymentGateway gateway;
  @Autowired private OfficeRepository officeRepository;
  @Autowired private OfficeMembershipRepository officeMembershipRepository;

  @Autowired
  @Qualifier("billingKeyCipher") private TextCipher cipher;

  private BillingFixtures fixtures;

  @BeforeEach
  void seed() {
    fixtures = new BillingFixtures(jdbcTemplate);
    gateway.reset();
    fixtures.standardPrice("2026-12-STD", Instant.now().minus(400, ChronoUnit.DAYS));
    fixtures.purchaseTerms(BillingFixtures.PURCHASE_TERMS_VERSION);
  }

  private Long sharedTrialEvent;

  private static Instant daysFromNow(long days) {
    return Instant.now().plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
  }

  private record Ready(TestData.Staff owner, long subscriptionId, long consentId) {}

  private Ready ready(UnaryOperator<SubscriptionSeed> seed, boolean consent, boolean key) {
    TestData.Staff owner =
        new TestData(appUserRepository, officeRepository, officeMembershipRepository)
            .approvedStaff(OfficeMembershipRole.OWNER);
    long subscriptionId = seed.apply(fixtures.subscription(owner.office().getId())).insert();
    long consentId = 0;
    if (consent) {
      consentId =
          fixtures.consent(
              subscriptionId, owner.user().getId(), owner.office().getId(), 39_900, 3_627, 39_900);
    }
    if (key) {
      fixtures.billingCustomerWithKey(
          owner.office().getId(),
          "customer-" + owner.office().getId(),
          cipher.encrypt("billing-key"));
    }
    return new Ready(owner, subscriptionId, consentId);
  }

  private Ready trialDue(boolean consent, boolean key) {
    if (sharedTrialEvent == null) {
      sharedTrialEvent =
          fixtures.trialEvent(
              "GENERAL", "DURATION", daysFromNow(-90), daysFromNow(30), null, "ACTIVE");
    }
    long event = sharedTrialEvent;
    return ready(
        seed -> seed.status("TRIAL").trial(event, daysFromNow(-61), daysFromNow(-1)), consent, key);
  }

  private Ready activeDue(Instant periodStart, Instant periodEnd) {
    return ready(seed -> seed.status("ACTIVE").period(periodStart, periodEnd), true, true);
  }

  private Map<String, Object> subscription(Ready ready) {
    return jdbcTemplate.queryForMap(
        "SELECT * FROM subscription WHERE id = ?", ready.subscriptionId());
  }

  private long count(String sql) {
    return jdbcTemplate.queryForObject(sql, Long.class);
  }

  @Test
  void 첫_청구는_무료_종료_시각을_회차_시작으로_청구서를_만들고_구독중으로_바꾸며_같은_키로_다시_실행하지_않는다() {
    Ready ready = trialDue(true, true);
    Instant scheduled =
        ((Timestamp) subscription(ready).get("first_billing_scheduled_at")).toInstant();
    jobService.runFirstCharges(Instant.now());
    Map<String, Object> sub = subscription(ready);
    assertThat(sub.get("status")).isEqualTo("ACTIVE");
    assertThat(((Timestamp) sub.get("current_period_start")).toInstant()).isEqualTo(scheduled);
    assertThat(((Timestamp) sub.get("cycle_anchor_at")).toInstant()).isEqualTo(scheduled);
    assertThat(((Timestamp) sub.get("current_period_end")).toInstant())
        .isEqualTo(BillingPeriods.boundary(scheduled, 1));
    assertThat(count("SELECT count(*) FROM billing_invoice WHERE status = 'PAID'")).isEqualTo(1);
    Map<String, Object> run =
        jdbcTemplate.queryForMap("SELECT job_name, status, idempotency_key FROM system_job_run");
    assertThat(run.get("job_name")).isEqualTo("billing-first-charge");
    assertThat(run.get("status")).isEqualTo("SUCCESS");
    assertThat((String) run.get("idempotency_key"))
        .isEqualTo("first-charge:" + ready.subscriptionId() + ":" + scheduled.getEpochSecond());
    jobService.runFirstCharges(Instant.now());
    assertThat(count("SELECT count(*) FROM billing_invoice")).isEqualTo(1);
    assertThat(gateway.charges()).hasSize(1);
  }

  @Test
  void 첫_청구_시각이_아직_오지_않은_체험은_청구하지_않는다() {
    long event =
        fixtures.trialEvent(
            "GENERAL", "DURATION", daysFromNow(-90), daysFromNow(30), null, "ACTIVE");
    Ready ready =
        ready(
            seed -> seed.status("TRIAL").trial(event, daysFromNow(-10), daysFromNow(50)),
            true,
            true);
    jobService.runFirstCharges(Instant.now());
    assertThat(subscription(ready).get("status")).isEqualTo("TRIAL");
    assertThat(count("SELECT count(*) FROM billing_invoice")).isZero();
  }

  @Test
  void 첫_청구에서_카드나_동의가_없으면_종료하지_않고_결제_전_상태로_두며_청구하지_않는다() {
    Ready noKey = trialDue(true, false);
    Ready noConsent = trialDue(false, true);
    jobService.runFirstCharges(Instant.now());
    for (Ready ready : List.of(noKey, noConsent)) {
      Map<String, Object> sub = subscription(ready);
      assertThat(sub.get("status")).isEqualTo("PRE_PAYMENT");
      assertThat(sub.get("pre_payment_since")).isNotNull();
      assertThat(sub.get("ended_at")).isNull();
    }
    assertThat(count("SELECT count(*) FROM billing_invoice")).isZero();
    assertThat(gateway.charges()).isEmpty();
  }

  @Test
  void 유효한_동의와_카드가_있는데_첫_결제가_실패하면_구독을_종료하고_반환기간에_들어간다() {
    Ready ready = trialDue(true, true);
    gateway.respondWith(PaymentStatus.FAILED);
    jobService.runFirstCharges(Instant.now());
    Map<String, Object> sub = subscription(ready);
    assertThat(sub.get("status")).isEqualTo("ENDED");
    assertThat(sub.get("ended_at")).isNotNull();
    assertThat(jdbcTemplate.queryForObject("SELECT status FROM billing_invoice", String.class))
        .isEqualTo("FAILED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT next_retry_at FROM billing_invoice", Timestamp.class))
        .isNull();
  }

  @Test
  void 첫_결제가_확인_중이면_구독을_종료로_바꾸고_뒤에_성공이_확인되면_구독중으로_돌린다() {
    Ready ready = trialDue(true, true);
    gateway.respondWith(PaymentStatus.VERIFYING);
    jobService.runFirstCharges(Instant.now());
    assertThat(subscription(ready).get("status")).isEqualTo("ENDED");
    assertThat(jdbcTemplate.queryForObject("SELECT status FROM billing_invoice", String.class))
        .isEqualTo("VERIFYING");
    String orderId =
        jdbcTemplate.queryForObject("SELECT pg_order_id FROM billing_charge_attempt", String.class);
    gateway.resolve(orderId, PaymentStatus.SUCCEEDED);
    jobService.runReconciliation();
    assertThat(jdbcTemplate.queryForObject("SELECT status FROM billing_invoice", String.class))
        .isEqualTo("PAID");
    assertThat(
            jdbcTemplate.queryForObject("SELECT result FROM billing_charge_attempt", String.class))
        .isEqualTo("SUCCESS");
    assertThat(subscription(ready).get("status")).isEqualTo("ACTIVE");
    assertThat(subscription(ready).get("ended_at")).isNull();
  }

  @Test
  void 확인_중인_결제가_실패로_확인되면_청구서와_시도를_실패로_바꾸고_진행_중이면_그대로_둔다() {
    Ready ready = trialDue(true, true);
    gateway.respondWith(PaymentStatus.VERIFYING);
    jobService.runFirstCharges(Instant.now());
    jobService.runReconciliation();
    assertThat(jdbcTemplate.queryForObject("SELECT status FROM billing_invoice", String.class))
        .isEqualTo("VERIFYING");
    String orderId =
        jdbcTemplate.queryForObject("SELECT pg_order_id FROM billing_charge_attempt", String.class);
    gateway.resolve(orderId, PaymentStatus.FAILED);
    jobService.runReconciliation();
    assertThat(jdbcTemplate.queryForObject("SELECT status FROM billing_invoice", String.class))
        .isEqualTo("FAILED");
    assertThat(
            jdbcTemplate.queryForObject("SELECT result FROM billing_charge_attempt", String.class))
        .isEqualTo("FAILED");
    assertThat(subscription(ready).get("status")).isEqualTo("ENDED");
  }

  @Test
  void 체험_중_해지가_발효되면_청구하지_않고_해지_발효_시각에_종료하며_동의_철회_사건을_남긴다() {
    Ready ready = trialDue(true, true);
    Instant effective = daysFromNow(-1);
    jdbcTemplate.update(
        "INSERT INTO subscription_cancellation (subscription_id, requested_at, effective_at,"
            + " cancelled_by_membership_id) VALUES (?, ?, ?, ?)",
        ready.subscriptionId(),
        Timestamp.from(daysFromNow(-30)),
        Timestamp.from(effective),
        ready.owner().membership().getId());
    jobService.runFirstCharges(Instant.now());
    Map<String, Object> sub = subscription(ready);
    assertThat(sub.get("status")).isEqualTo("ENDED");
    assertThat(((Timestamp) sub.get("ended_at")).toInstant().truncatedTo(ChronoUnit.MILLIS))
        .isEqualTo(effective.truncatedTo(ChronoUnit.MILLIS));
    assertThat(count("SELECT count(*) FROM billing_invoice")).isZero();
    assertThat(count("SELECT count(*) FROM subscription_purchase_consent WHERE agreed = false"))
        .isEqualTo(1);
  }

  @Test
  void 갱신_청구는_원래_기준일로_다음_경계를_계산해_회차를_이어가고_말일_다음_달에는_원래_일자로_돌아간다() {
    Instant anchor = Instant.parse("2027-01-31T09:00:00+09:00");
    Instant periodStart = Instant.parse("2027-02-28T09:00:00+09:00");
    Instant periodEnd = Instant.parse("2027-02-28T09:00:00+09:00");
    Ready ready =
        ready(
            seed -> {
              SubscriptionSeed configured = seed.status("ACTIVE").period(anchor, periodEnd);
              return configured;
            },
            true,
            true);
    jobService.runRenewals(Instant.parse("2027-03-01T00:00:00+09:00"));
    Map<String, Object> sub = subscription(ready);
    assertThat(sub.get("status")).isEqualTo("ACTIVE");
    assertThat(((Timestamp) sub.get("current_period_start")).toInstant()).isEqualTo(periodStart);
    assertThat(((Timestamp) sub.get("current_period_end")).toInstant())
        .isEqualTo(Instant.parse("2027-03-31T09:00:00+09:00"));
    assertThat(((Timestamp) sub.get("cycle_anchor_at")).toInstant()).isEqualTo(anchor);
  }

  @Test
  void 갱신이_아직_도래하지_않았으면_청구하지_않고_해지_예약이_발효됐으면_종료한다() {
    Ready notDue = activeDue(daysFromNow(-10), daysFromNow(20));
    jobService.runRenewals(Instant.now());
    assertThat(count("SELECT count(*) FROM billing_invoice")).isZero();
    assertThat(subscription(notDue).get("status")).isEqualTo("ACTIVE");

    Ready cancelled = activeDue(daysFromNow(-31), daysFromNow(-1));
    jdbcTemplate.update(
        "INSERT INTO subscription_cancellation (subscription_id, requested_at, effective_at,"
            + " cancelled_by_membership_id) VALUES (?, ?, ?, ?)",
        cancelled.subscriptionId(),
        Timestamp.from(daysFromNow(-20)),
        Timestamp.from(daysFromNow(-1)),
        cancelled.owner().membership().getId());
    jobService.runRenewals(Instant.now());
    assertThat(subscription(cancelled).get("status")).isEqualTo("ENDED");
    assertThat(count("SELECT count(*) FROM billing_invoice")).isZero();
    assertThat(gateway.charges()).isEmpty();
  }

  @Test
  void 갱신이_실패하면_미납_유예가_시작되고_첫_재시도는_D0_1시간_뒤다() {
    Instant periodEnd = daysFromNow(-1);
    Ready ready = activeDue(periodEnd.minus(30, ChronoUnit.DAYS), periodEnd);
    gateway.respondWith(PaymentStatus.FAILED);
    jobService.runRenewals(Instant.now());
    Map<String, Object> sub = subscription(ready);
    assertThat(sub.get("status")).isEqualTo("PAST_DUE_GRACE");
    assertThat(((Timestamp) sub.get("past_due_since")).toInstant().truncatedTo(ChronoUnit.MILLIS))
        .isEqualTo(periodEnd.truncatedTo(ChronoUnit.MILLIS));
    Map<String, Object> invoice =
        jdbcTemplate.queryForMap("SELECT status, next_retry_at, due_at FROM billing_invoice");
    assertThat(invoice.get("status")).isEqualTo("FAILED");
    assertThat(((Timestamp) invoice.get("next_retry_at")).toInstant())
        .isEqualTo(((Timestamp) invoice.get("due_at")).toInstant().plus(Duration.ofHours(1)));
  }

  @Test
  void 재시도는_D0_1시간_3일_7일_순서로_최대_네_번이고_사용불가_카드는_자동_재시도를_멈춘다() {
    Instant periodEnd = daysFromNow(-10);
    Ready ready = activeDue(periodEnd.minus(30, ChronoUnit.DAYS), periodEnd);
    gateway.respondWith(PaymentStatus.FAILED);
    jobService.runRenewals(Instant.now());
    Instant dueAt =
        ((Timestamp)
                jdbcTemplate.queryForObject("SELECT due_at FROM billing_invoice", Timestamp.class))
            .toInstant();
    List<Duration> expectedOffsets = List.of(Duration.ofDays(3), Duration.ofDays(7));
    int minutes = 2;
    for (Duration offset : expectedOffsets) {
      jobService.runRetries(Instant.now());
      Timestamp next =
          jdbcTemplate.queryForObject("SELECT next_retry_at FROM billing_invoice", Timestamp.class);
      assertThat(next.toInstant()).isEqualTo(dueAt.plus(offset));
      jdbcTemplate.update(
          "UPDATE billing_invoice SET next_retry_at = now() - make_interval(mins => ?)", minutes++);
    }
    jobService.runRetries(Instant.now());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT next_retry_at FROM billing_invoice", Timestamp.class))
        .isNull();
    assertThat(count("SELECT count(*) FROM billing_charge_attempt")).isEqualTo(4);
    assertThat(subscription(ready).get("status")).isEqualTo("PAST_DUE_GRACE");
  }

  @Test
  void 사용불가_카드_오류는_자동_재시도_예정을_만들지_않는다() {
    Instant periodEnd = daysFromNow(-1);
    Ready ready = activeDue(periodEnd.minus(30, ChronoUnit.DAYS), periodEnd);
    gateway.respondWith(PaymentStatus.FAILED);
    gateway.failWithCode("EXPIRED_CARD");
    jobService.runRenewals(Instant.now());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT next_retry_at FROM billing_invoice", Timestamp.class))
        .isNull();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT failure_category FROM billing_charge_attempt", String.class))
        .isEqualTo("CARD_UNUSABLE");
    assertThat(subscription(ready).get("status")).isEqualTo("PAST_DUE_GRACE");
  }

  @Test
  void 재시도가_성공하면_원래_청구_기간을_유지한_채_구독중으로_돌아가고_남은_재시도가_사라진다() {
    Instant periodEnd = daysFromNow(-1);
    Ready ready = activeDue(periodEnd.minus(30, ChronoUnit.DAYS), periodEnd);
    gateway.respondWith(PaymentStatus.FAILED);
    jobService.runRenewals(Instant.now());
    Map<String, Object> failed =
        jdbcTemplate.queryForMap(
            "SELECT billing_period_start, billing_period_end FROM billing_invoice");
    gateway.respondWith(PaymentStatus.SUCCEEDED);
    jdbcTemplate.update("UPDATE billing_invoice SET next_retry_at = now() - interval '1 minute'");
    jobService.runRetries(Instant.now());
    Map<String, Object> sub = subscription(ready);
    assertThat(sub.get("status")).isEqualTo("ACTIVE");
    assertThat(sub.get("past_due_since")).isNull();
    assertThat(sub.get("current_period_start")).isEqualTo(failed.get("billing_period_start"));
    assertThat(sub.get("current_period_end")).isEqualTo(failed.get("billing_period_end"));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT next_retry_at FROM billing_invoice", Timestamp.class))
        .isNull();
    assertThat(jdbcTemplate.queryForObject("SELECT status FROM billing_invoice", String.class))
        .isEqualTo("PAID");
  }

  @Test
  void 인원이_늘어_청구액이_동의_한도를_넘으면_청구하지_않고_미납_유예로_두어_새_동의를_기다린다() {
    Instant periodEnd = daysFromNow(-1);
    Ready ready = activeDue(periodEnd.minus(30, ChronoUnit.DAYS), periodEnd);
    jdbcTemplate.update(
        "INSERT INTO office_membership (office_id, user_id, role, status, request_source, approved_at)"
            + " VALUES (?, ?, 'AGENT', 'APPROVED', 'REGISTRATION', now())",
        ready.owner().office().getId(),
        appUserRepository.saveAndFlush(AppUserBuilder.anAppUser().build()).getId());
    jobService.runRenewals(Instant.now());
    assertThat(subscription(ready).get("status")).isEqualTo("PAST_DUE_GRACE");
    assertThat(count("SELECT count(*) FROM billing_invoice")).isZero();
    assertThat(gateway.charges()).isEmpty();
  }

  @Test
  void 무료_요금은_PG_청구서를_발행하지_않고_운영_작업_실패로_남긴다() {
    Instant periodEnd = daysFromNow(-1);
    Ready ready = activeDue(periodEnd.minus(30, ChronoUnit.DAYS), periodEnd);
    fixtures.price("ZERO-UNSUPPORTED", "STANDARD", daysFromNow(-2), "ACTIVE", 0);

    jobService.runRenewals(Instant.now());

    assertThat(count("SELECT count(*) FROM billing_invoice")).isZero();
    assertThat(gateway.charges()).isEmpty();
    assertThat(subscription(ready).get("status")).isEqualTo("ACTIVE");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM system_job_run WHERE job_name = 'billing-renewal'",
                String.class))
        .isEqualTo("FAILED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT error_code FROM system_job_run WHERE job_name = 'billing-renewal'",
                String.class))
        .isEqualTo("BILLING_AMOUNT_UNSUPPORTED");
  }

  @Test
  void 이전에_저장된_무료_청구서도_PG에_보내지_않고_실패로_표시한다() {
    Instant periodEnd = daysFromNow(-1);
    Ready ready = activeDue(periodEnd.minus(30, ChronoUnit.DAYS), periodEnd);
    fixtures
        .invoice(ready.owner().office().getId(), ready.subscriptionId(), ready.consentId())
        .amount(0)
        .status("UNBILLED")
        .period(periodEnd, BillingPeriods.boundary(periodEnd, 1))
        .insert();

    jobService.runRenewals(Instant.now());

    assertThat(gateway.charges()).isEmpty();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM system_job_run WHERE job_name = 'billing-renewal'",
                String.class))
        .isEqualTo("FAILED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT error_code FROM system_job_run WHERE job_name = 'billing-renewal'",
                String.class))
        .isEqualTo("BILLING_AMOUNT_UNSUPPORTED");
  }

  @Test
  void 이전에_실패한_무료_청구서의_재시도도_PG에_보내지_않고_실패로_표시한다() {
    Instant periodEnd = daysFromNow(-1);
    Ready ready =
        ready(
            seed ->
                seed.status("PAST_DUE_GRACE")
                    .period(periodEnd.minus(30, ChronoUnit.DAYS), periodEnd)
                    .pastDueSince(periodEnd),
            true,
            true);
    fixtures
        .invoice(ready.owner().office().getId(), ready.subscriptionId(), ready.consentId())
        .amount(0)
        .status("FAILED")
        .period(periodEnd, BillingPeriods.boundary(periodEnd, 1))
        .insert();
    jdbcTemplate.update("UPDATE billing_invoice SET next_retry_at = now() - interval '1 minute'");

    jobService.runRetries(Instant.now());

    assertThat(gateway.charges()).isEmpty();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM system_job_run WHERE job_name = 'billing-retry'", String.class))
        .isEqualTo("FAILED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT error_code FROM system_job_run WHERE job_name = 'billing-retry'",
                String.class))
        .isEqualTo("BILLING_AMOUNT_UNSUPPORTED");
  }

  @Test
  void 가격_인상에_동의하지_않으면_갱신_청구서를_만들지_않고_새_청구기간_시작에_종료하며_안내를_무동의_종료로_바꾼다() {
    Instant periodEnd = daysFromNow(-1);
    Ready ready = activeDue(periodEnd.minus(30, ChronoUnit.DAYS), periodEnd);
    long from = jdbcTemplate.queryForObject("SELECT id FROM price_schedule LIMIT 1", Long.class);
    long to = fixtures.price("2027-09-STD", "STANDARD", daysFromNow(-1), "ACTIVE", 49_900);
    long noticeId =
        fixtures.notice(ready.owner().office().getId(), ready.subscriptionId(), from, to).insert();
    jdbcTemplate.update(
        "UPDATE pricing_change_notice SET notified_at = now() - interval '40 days',"
            + " effective_at = ?, applies_from_period_start = ?, response_due_at = ? WHERE id = ?",
        Timestamp.from(periodEnd),
        Timestamp.from(periodEnd),
        Timestamp.from(periodEnd),
        noticeId);
    jobService.runRenewals(Instant.now());
    Map<String, Object> sub = subscription(ready);
    assertThat(sub.get("status")).isEqualTo("ENDED");
    assertThat(((Timestamp) sub.get("ended_at")).toInstant().truncatedTo(ChronoUnit.MILLIS))
        .isEqualTo(periodEnd.truncatedTo(ChronoUnit.MILLIS));
    assertThat(count("SELECT count(*) FROM billing_invoice")).isZero();
    assertThat(
            jdbcTemplate.queryForObject("SELECT decision FROM pricing_change_notice", String.class))
        .isEqualTo("ENDED_NO_CONSENT");
    assertThat(gateway.charges()).isEmpty();
  }

  @Test
  void 미납_유예는_D0_8일에_미납_제한으로_바뀌고_D0_30일에_미납_청구를_취소하며_종료한다() {
    Instant d0 = daysFromNow(-9);
    Ready ready =
        ready(
            seed ->
                seed.status("PAST_DUE_GRACE")
                    .period(d0.minus(30, ChronoUnit.DAYS), d0)
                    .pastDueSince(d0),
            true,
            true);
    long invoiceId =
        fixtures
            .invoice(ready.owner().office().getId(), ready.subscriptionId(), ready.consentId())
            .status("FAILED")
            .period(d0, d0.plus(30, ChronoUnit.DAYS))
            .insert();
    jobService.runDelinquency(Instant.now());
    assertThat(subscription(ready).get("status")).isEqualTo("PAST_DUE_RESTRICTED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM billing_invoice WHERE id = ?", String.class, invoiceId))
        .isEqualTo("FAILED");
    jobService.runDelinquency(d0.plus(30, ChronoUnit.DAYS).minusSeconds(1));
    assertThat(subscription(ready).get("status")).isEqualTo("PAST_DUE_RESTRICTED");
    jobService.runDelinquency(d0.plus(30, ChronoUnit.DAYS));
    Map<String, Object> sub = subscription(ready);
    assertThat(sub.get("status")).isEqualTo("ENDED");
    assertThat(((Timestamp) sub.get("ended_at")).toInstant().truncatedTo(ChronoUnit.MILLIS))
        .isEqualTo(d0.plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM billing_invoice WHERE id = ?", String.class, invoiceId))
        .isEqualTo("CANCELLED");
  }

  @Test
  void 미납_유예는_8일_전에는_제한되지_않는다() {
    Instant d0 = daysFromNow(-7);
    Ready ready =
        ready(
            seed ->
                seed.status("PAST_DUE_GRACE")
                    .period(d0.minus(30, ChronoUnit.DAYS), d0)
                    .pastDueSince(d0),
            true,
            true);
    jobService.runDelinquency(Instant.now());
    assertThat(subscription(ready).get("status")).isEqualTo("PAST_DUE_GRACE");
  }

  @Test
  void 결제_전_상태는_시작_30일_뒤_종료되고_그_전에는_유지된다() {
    Instant since = daysFromNow(-30);
    Ready expired = ready(seed -> seed.status("PRE_PAYMENT").prePaymentSince(since), false, false);
    Ready waiting =
        ready(seed -> seed.status("PRE_PAYMENT").prePaymentSince(daysFromNow(-29)), false, false);
    jobService.runPrePaymentExpiry(Instant.now());
    assertThat(subscription(expired).get("status")).isEqualTo("ENDED");
    assertThat(
            ((Timestamp) subscription(expired).get("ended_at"))
                .toInstant()
                .truncatedTo(ChronoUnit.MILLIS))
        .isEqualTo(since.plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS));
    assertThat(subscription(waiting).get("status")).isEqualTo("PRE_PAYMENT");
  }

  @Test
  void 같은_멱등키의_성공한_작업은_다시_실행하지_않고_실패한_작업은_재시도_횟수를_올려_다시_실행한다() {
    int[] runs = {0};
    assertThat(jobRunService.runOnce("job", null, "key-1", null, () -> runs[0]++)).isTrue();
    assertThat(jobRunService.runOnce("job", null, "key-1", null, () -> runs[0]++)).isFalse();
    assertThat(runs[0]).isEqualTo(1);
    assertThat(
            jobRunService.runOnce(
                "job",
                null,
                "key-2",
                null,
                () -> {
                  throw new IllegalStateException("실패");
                }))
        .isFalse();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM system_job_run WHERE idempotency_key = 'key-2'", String.class))
        .isEqualTo("FAILED");
    assertThat(jobRunService.runOnce("job", null, "key-2", null, () -> runs[0]++)).isTrue();
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT status, attempt_count FROM system_job_run WHERE idempotency_key = 'key-2'");
    assertThat(row).containsEntry("status", "SUCCESS").containsEntry("attempt_count", 2);
  }

  @Test
  void 이미_처리_중인_청구서를_다시_청구하면_거절하고_결제대행사를_호출하지_않는다() {
    Ready ready = trialDue(true, true);
    gateway.respondWith(PaymentStatus.VERIFYING);
    jobService.runFirstCharges(Instant.now());
    Long invoiceId = jdbcTemplate.queryForObject("SELECT id FROM billing_invoice", Long.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> chargeService.charge(invoiceId))
        .isInstanceOf(com.sairo.be.global.error.BusinessException.class);
    assertThat(gateway.charges()).hasSize(1);
    assertThat(subscription(ready).get("status")).isEqualTo("ENDED");
  }

  private record Crashed(Ready ready, String orderId, Instant periodEnd) {}

  /** 갱신 청구가 청구서와 시도를 처리 중으로 저장한 뒤 결과 반영 없이 프로세스가 중단된 상태를 만든다. */
  private Crashed crashedRenewal(Duration age) {
    Instant periodEnd = daysFromNow(-1);
    Ready ready = activeDue(daysFromNow(-31), periodEnd);
    gateway.respondWith(PaymentStatus.VERIFYING);
    jobService.runRenewals(Instant.now());
    String orderId =
        jdbcTemplate.queryForObject("SELECT pg_order_id FROM billing_charge_attempt", String.class);
    Timestamp at = Timestamp.from(Instant.now().minus(age));
    jdbcTemplate.update("UPDATE billing_invoice SET status = 'PROCESSING'");
    jdbcTemplate.update(
        "UPDATE billing_charge_attempt SET result = 'PROCESSING', attempted_at = ?", at);
    jdbcTemplate.update(
        "UPDATE system_job_run SET status = 'RUNNING', finished_at = NULL, started_at = ?"
            + " WHERE job_name = 'billing-renewal'",
        at);
    return new Crashed(ready, orderId, periodEnd);
  }

  private String invoiceStatus() {
    return jdbcTemplate.queryForObject("SELECT status FROM billing_invoice", String.class);
  }

  private String attemptResult() {
    return jdbcTemplate.queryForObject("SELECT result FROM billing_charge_attempt", String.class);
  }

  @Test
  void 호출_직후_결과_반영_전에_중단된_갱신은_토스_조회로_승인을_확정하고_새_주문을_만들지_않는다() {
    Crashed crashed = crashedRenewal(Duration.ofHours(2));
    gateway.resolve(crashed.orderId(), PaymentStatus.SUCCEEDED);

    jobService.recoverInterruptedCharges(Instant.now());
    assertThat(invoiceStatus()).isEqualTo("VERIFYING");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM system_job_run WHERE job_name = 'billing-renewal'",
                String.class))
        .isEqualTo("FAILED");

    jobService.runReconciliation();
    assertThat(invoiceStatus()).isEqualTo("PAID");
    assertThat(attemptResult()).isEqualTo("SUCCESS");
    Map<String, Object> sub = subscription(crashed.ready());
    assertThat(sub.get("status")).isEqualTo("ACTIVE");
    assertThat(((Timestamp) sub.get("current_period_end")).toInstant())
        .isAfter(crashed.periodEnd());
    assertThat(gateway.charges()).hasSize(1);
    assertThat(count("SELECT count(*) FROM billing_charge_attempt")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM billing_invoice")).isEqualTo(1);
  }

  @Test
  void 호출_전에_중단된_갱신은_토스에_주문이_없으면_실패로_확정하고_미납_유예로_들어간다() {
    Crashed crashed = crashedRenewal(Duration.ofHours(2));
    gateway.reset();

    jobService.recoverInterruptedCharges(Instant.now());
    jobService.runReconciliation();

    assertThat(invoiceStatus()).isEqualTo("FAILED");
    assertThat(attemptResult()).isEqualTo("FAILED");
    assertThat(subscription(crashed.ready()).get("status")).isEqualTo("PAST_DUE_GRACE");
    assertThat(gateway.charges()).isEmpty();
    assertThat(count("SELECT count(*) FROM billing_charge_attempt")).isEqualTo(1);
  }

  @Test
  void 반영까지_끝난_갱신은_회수_대상이_아니다() {
    Instant periodEnd = daysFromNow(-1);
    Ready ready = activeDue(daysFromNow(-31), periodEnd);
    jobService.runRenewals(Instant.now());
    assertThat(invoiceStatus()).isEqualTo("PAID");

    assertThat(chargeService.recoverStaleProcessing(Instant.now().plus(Duration.ofDays(1))))
        .isZero();
    jobService.recoverInterruptedCharges(Instant.now().plus(Duration.ofDays(1)));

    assertThat(invoiceStatus()).isEqualTo("PAID");
    assertThat(attemptResult()).isEqualTo("SUCCESS");
    assertThat(subscription(ready).get("status")).isEqualTo("ACTIVE");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM system_job_run WHERE job_name = 'billing-renewal'",
                String.class))
        .isEqualTo("SUCCESS");
    assertThat(gateway.charges()).hasSize(1);
  }

  @Test
  void 기준_시간이_지나지_않은_처리_중_시도와_실행_중_작업은_건드리지_않는다() {
    crashedRenewal(Duration.ofMinutes(1));

    jobService.recoverInterruptedCharges(Instant.now());

    assertThat(invoiceStatus()).isEqualTo("PROCESSING");
    assertThat(attemptResult()).isEqualTo("PROCESSING");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM system_job_run WHERE job_name = 'billing-renewal'",
                String.class))
        .isEqualTo("RUNNING");
  }

  @Test
  void 결과를_확인하기_전에는_회수된_작업이_다시_시작돼도_새_주문으로_청구하지_않는다() {
    crashedRenewal(Duration.ofHours(2));

    jobService.recoverInterruptedCharges(Instant.now());
    jobService.runRenewals(Instant.now());

    assertThat(invoiceStatus()).isEqualTo("VERIFYING");
    assertThat(gateway.charges()).hasSize(1);
    assertThat(count("SELECT count(*) FROM billing_charge_attempt")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM billing_invoice")).isEqualTo(1);
  }

  @Test
  void 청구서만_발행하고_중단된_갱신은_같은_청구서를_이어서_청구한다() {
    Instant periodEnd = daysFromNow(-1);
    Ready ready = activeDue(daysFromNow(-31), periodEnd);
    long invoiceId =
        fixtures
            .invoice(ready.owner().office().getId(), ready.subscriptionId(), ready.consentId())
            .status("UNBILLED")
            .period(periodEnd, periodEnd.plus(Duration.ofDays(30)))
            .insert();
    jdbcTemplate.update(
        "INSERT INTO system_job_run (job_name, office_id, idempotency_key, status, scheduled_at,"
            + " started_at) VALUES ('billing-renewal', ?, ?, 'RUNNING', ?, ?)",
        ready.owner().office().getId(),
        "renewal:" + ready.subscriptionId() + ":" + periodEnd.getEpochSecond(),
        Timestamp.from(periodEnd),
        Timestamp.from(Instant.now().minus(Duration.ofHours(2))));

    jobService.recoverInterruptedCharges(Instant.now());
    jobService.runRenewals(Instant.now());

    assertThat(count("SELECT count(*) FROM billing_invoice")).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM billing_invoice WHERE id = ?", String.class, invoiceId))
        .isEqualTo("PAID");
    assertThat(gateway.charges()).hasSize(1);
    assertThat(subscription(ready).get("status")).isEqualTo("ACTIVE");
  }
}
