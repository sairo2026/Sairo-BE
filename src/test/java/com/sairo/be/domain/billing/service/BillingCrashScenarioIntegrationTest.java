package com.sairo.be.domain.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import com.sairo.be.domain.billing.service.SystemJobRunService.RerunOutcome;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.PaymentGateway.PaymentStatus;
import com.sairo.be.global.port.stub.StubPaymentGateway;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StaffRole;
import com.sairo.be.global.security.StepUpState;
import com.sairo.be.global.support.TextCipher;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.BillingFixtures;
import com.sairo.be.support.fixture.TestData;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * 청구 중단 복구 시나리오를 이어서 검증한다. 호출 전 중단의 전체 연쇄, 오래 이어지는 확인 중, 결과 중복 전달, 미납 제한과 복구, 동시 재시도, 운영자 재실행 경로를
 * 다룬다. 결제대행사는 모의 구현만 쓴다.
 */
@IntegrationTest
class BillingCrashScenarioIntegrationTest extends AuthIntegrationTestBase {

  @Autowired private BillingJobService jobService;
  @Autowired private OfficeCloseService officeCloseService;
  @Autowired private StepUpState stepUpState;
  @Autowired private BillingChargeService chargeService;
  @Autowired private SystemJobRunService jobRunService;
  @MockitoSpyBean private StubPaymentGateway gateway;
  @MockitoSpyBean private SubscriptionTransitions transitions;
  @Autowired private OfficeRepository officeRepository;
  @Autowired private OfficeMembershipRepository officeMembershipRepository;

  @Autowired
  @Qualifier("billingKeyCipher") private TextCipher cipher;

  private BillingFixtures fixtures;
  private ExecutorService executor;

  @BeforeEach
  void seed() {
    fixtures = new BillingFixtures(jdbcTemplate);
    gateway.reset();
    fixtures.standardPrice("2026-12-STD-C", Instant.now().minus(400, ChronoUnit.DAYS));
    fixtures.purchaseTerms(BillingFixtures.PURCHASE_TERMS_VERSION);
    executor = Executors.newFixedThreadPool(4);
  }

  @AfterEach
  void shutdown() {
    executor.shutdownNow();
    gateway.reset();
  }

  private static Instant daysFromNow(long days) {
    return Instant.now().plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
  }

  private record Due(
      long subscriptionId, long officeId, long consentId, Instant periodEnd, long ownerUserId) {}

  private Due subscriptionIn(
      String status, Instant periodStart, Instant periodEnd, Instant pastDue) {
    TestData.Staff owner =
        new TestData(appUserRepository, officeRepository, officeMembershipRepository)
            .approvedStaff(OfficeMembershipRole.OWNER);
    var seed =
        fixtures.subscription(owner.office().getId()).status(status).period(periodStart, periodEnd);
    if (pastDue != null) {
      seed = seed.pastDueSince(pastDue);
    }
    long subscriptionId = seed.insert();
    long consentId =
        fixtures.consent(
            subscriptionId, owner.user().getId(), owner.office().getId(), 39_900, 3_627, 39_900);
    fixtures.billingCustomerWithKey(
        owner.office().getId(),
        "customer-" + owner.office().getId(),
        cipher.encrypt("billing-key"));
    return new Due(
        subscriptionId, owner.office().getId(), consentId, periodEnd, owner.user().getId());
  }

  private Due activeDue() {
    Instant periodEnd = daysFromNow(-1);
    return subscriptionIn("ACTIVE", daysFromNow(-31), periodEnd, null);
  }

  private long count(String sql) {
    return jdbcTemplate.queryForObject(sql, Long.class);
  }

  private String invoiceStatus() {
    return jdbcTemplate.queryForObject("SELECT status FROM billing_invoice", String.class);
  }

  private String subscriptionStatus(Due due) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM subscription WHERE id = ?", String.class, due.subscriptionId());
  }

  private long notices(Due due) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM notification WHERE recipient_user_id = ?",
        Long.class,
        due.ownerUserId());
  }

  private String lastOrderId() {
    return jdbcTemplate.queryForObject(
        "SELECT pg_order_id FROM billing_charge_attempt ORDER BY attempt_no DESC LIMIT 1",
        String.class);
  }

  /** 청구서와 시도를 처리 중으로 저장한 뒤 결제대행사에 아무 요청도 가지 않은 채 중단된 상태를 만든다. */
  private Due crashBeforePgCall(Duration age) {
    Due due = activeDue();
    gateway.respondWith(PaymentStatus.VERIFYING);
    jobService.runRenewals(Instant.now());
    Timestamp at = Timestamp.from(Instant.now().minus(age));
    jdbcTemplate.update("UPDATE billing_invoice SET status = 'PROCESSING'");
    jdbcTemplate.update(
        "UPDATE billing_charge_attempt SET result = 'PROCESSING', attempted_at = ?", at);
    jdbcTemplate.update(
        "UPDATE system_job_run SET status = 'RUNNING', finished_at = NULL, started_at = ?"
            + " WHERE job_name = 'billing-renewal'",
        at);
    gateway.reset();
    return due;
  }

  private <T> List<Object> runConcurrently(int threads, Callable<T> task) throws Exception {
    CyclicBarrier barrier = new CyclicBarrier(threads);
    List<Future<Object>> futures = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      futures.add(
          executor.submit(
              () -> {
                barrier.await();
                try {
                  return task.call();
                } catch (Exception e) {
                  return e;
                }
              }));
    }
    List<Object> results = new ArrayList<>();
    for (Future<Object> future : futures) {
      results.add(future.get());
    }
    return results;
  }

  @Test
  void 호출_전_중단은_실패로_확정돼도_안내는_한_번이고_첫_재시도가_새_주문으로_청구해_구독을_복구한다() {
    Due due = crashBeforePgCall(Duration.ofHours(2));
    String crashedOrder = lastOrderId();
    long before = notices(due);

    jobService.recoverInterruptedCharges(Instant.now());
    jobService.runReconciliation();

    assertThat(invoiceStatus()).isEqualTo("FAILED");
    assertThat(subscriptionStatus(due)).isEqualTo("PAST_DUE_GRACE");
    assertThat(gateway.charges()).isEmpty();
    assertThat(notices(due)).isEqualTo(before + 1);
    Instant dueAt =
        jdbcTemplate
            .queryForObject("SELECT due_at FROM billing_invoice", Timestamp.class)
            .toInstant();
    Instant nextRetry =
        jdbcTemplate
            .queryForObject("SELECT next_retry_at FROM billing_invoice", Timestamp.class)
            .toInstant();
    assertThat(nextRetry.truncatedTo(ChronoUnit.MILLIS))
        .isEqualTo(dueAt.plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MILLIS));

    jobService.recoverInterruptedCharges(Instant.now());
    jobService.runReconciliation();
    jobService.runRenewals(Instant.now());
    assertThat(gateway.charges()).isEmpty();
    assertThat(notices(due)).isEqualTo(before + 1);

    jdbcTemplate.update("UPDATE billing_invoice SET next_retry_at = now() - interval '1 minute'");
    jobService.runRetries(Instant.now());

    assertThat(gateway.charges()).hasSize(1);
    assertThat(gateway.charges().getFirst().orderId()).isNotEqualTo(crashedOrder);
    assertThat(count("SELECT count(*) FROM billing_charge_attempt")).isEqualTo(2);
    assertThat(count("SELECT count(DISTINCT idempotency_key) FROM billing_charge_attempt"))
        .isEqualTo(2);
    assertThat(invoiceStatus()).isEqualTo("PAID");
    assertThat(subscriptionStatus(due)).isEqualTo("ACTIVE");
    assertThat(notices(due)).isEqualTo(before + 1);
  }

  @Test
  void 확인_중이_며칠이_지나도_새_주문과_구독_변화_없이_유지되고_조회_예외도_상태를_바꾸지_않으며_늦은_승인은_한_번만_반영된다() {
    Due due = activeDue();
    gateway.respondWith(PaymentStatus.VERIFYING);
    jobService.runRenewals(Instant.now());
    String orderId = lastOrderId();
    long before = notices(due);

    for (long hours : new long[] {1, 24, 72, 8 * 24, 40 * 24}) {
      Instant at = Instant.now().plus(Duration.ofHours(hours));
      jobService.recoverInterruptedCharges(at);
      jobService.runRenewals(at);
      jobService.runRetries(at);
      jobService.runDelinquency(at);
      jobService.runReconciliation();
    }
    assertThat(invoiceStatus()).isEqualTo("VERIFYING");
    assertThat(subscriptionStatus(due)).isEqualTo("ACTIVE");
    assertThat(gateway.charges()).hasSize(1);
    assertThat(count("SELECT count(*) FROM billing_charge_attempt")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM billing_invoice")).isEqualTo(1);
    assertThat(notices(due)).isEqualTo(before);

    doThrow(new IllegalStateException("조회 시간 초과")).when(gateway).inquire(any());
    jobService.runReconciliation();
    assertThat(invoiceStatus()).isEqualTo("VERIFYING");
    doCallRealMethod().when(gateway).inquire(any());

    gateway.resolve(orderId, PaymentStatus.SUCCEEDED);
    jobService.runReconciliation();
    jobService.runReconciliation();

    assertThat(invoiceStatus()).isEqualTo("PAID");
    assertThat(subscriptionStatus(due)).isEqualTo("ACTIVE");
    assertThat(gateway.charges()).hasSize(1);
    assertThat(count("SELECT count(*) FROM billing_charge_attempt")).isEqualTo(1);
  }

  @Test
  void 같은_결과가_두_번_전달되어도_구독_기간과_안내는_한_번만_반영되고_완료된_청구서는_다시_청구하지_않는다() {
    Due due = activeDue();
    gateway.throwOnNextCharge(new IllegalStateException("응답 형식을 알 수 없음"));
    jobService.runRenewals(Instant.now());
    assertThat(invoiceStatus()).isEqualTo("VERIFYING");
    long invoiceId = count("SELECT id FROM billing_invoice");
    String orderId = lastOrderId();

    gateway.resolve(orderId, PaymentStatus.SUCCEEDED);
    assertThat(chargeService.reconcile(invoiceId)).isPresent();
    Timestamp periodEnd =
        jdbcTemplate.queryForObject(
            "SELECT current_period_end FROM subscription WHERE id = ?",
            Timestamp.class,
            due.subscriptionId());
    long afterFirst = notices(due);

    assertThat(chargeService.reconcile(invoiceId)).isEmpty();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT current_period_end FROM subscription WHERE id = ?",
                Timestamp.class,
                due.subscriptionId()))
        .isEqualTo(periodEnd);
    assertThat(notices(due)).isEqualTo(afterFirst);

    assertThatThrownBy(() -> chargeService.charge(invoiceId))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.INVALID_TRANSITION);
    assertThat(gateway.charges()).hasSize(1);
    assertThat(count("SELECT count(*) FROM billing_charge_attempt")).isEqualTo(1);
  }

  @Test
  void 미납_제한은_동시에_실행해도_한_번만_걸리고_안내도_한_번이며_결제가_성공하면_구독중으로_돌아온다() throws Exception {
    Instant d0 = daysFromNow(-9);
    Due due = subscriptionIn("PAST_DUE_GRACE", d0.minus(30, ChronoUnit.DAYS), d0, d0);
    long invoiceId =
        fixtures
            .invoice(due.officeId(), due.subscriptionId(), due.consentId())
            .status("FAILED")
            .period(d0, d0.plus(30, ChronoUnit.DAYS))
            .insert();
    long before = notices(due);

    runConcurrently(
        4,
        () -> {
          jobService.runDelinquency(Instant.now());
          return null;
        });

    assertThat(subscriptionStatus(due)).isEqualTo("PAST_DUE_RESTRICTED");
    assertThat(notices(due)).isEqualTo(before + 1);
    assertThat(
            count(
                "SELECT count(*) FROM system_job_run WHERE job_name = 'billing-delinquency-restrict'"))
        .isEqualTo(1);

    chargeService.charge(invoiceId);

    assertThat(subscriptionStatus(due)).isEqualTo("ACTIVE");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT past_due_since FROM subscription WHERE id = ?",
                Timestamp.class,
                due.subscriptionId()))
        .isNull();
    jobService.runDelinquency(Instant.now());
    assertThat(subscriptionStatus(due)).isEqualTo("ACTIVE");
    assertThat(gateway.charges()).hasSize(1);
  }

  @Test
  void 종료_직전_확인_중이던_청구가_종료_뒤_승인으로_확인되면_구독이_복구되고_미납_청구는_취소된다() {
    Instant d0 = daysFromNow(-31);
    Due due = subscriptionIn("PAST_DUE_RESTRICTED", d0.minus(30, ChronoUnit.DAYS), d0, d0);
    long retryInvoice =
        fixtures
            .invoice(due.officeId(), due.subscriptionId(), due.consentId())
            .status("FAILED")
            .period(d0, d0.plus(30, ChronoUnit.DAYS))
            .insert();
    gateway.respondWith(PaymentStatus.VERIFYING);
    assertThat(chargeService.charge(retryInvoice).isVerifying()).isTrue();

    jobService.runDelinquency(Instant.now());
    assertThat(subscriptionStatus(due)).isEqualTo("ENDED");

    gateway.resolve(lastOrderId(), PaymentStatus.SUCCEEDED);
    jobService.runReconciliation();

    assertThat(invoiceStatus()).isEqualTo("PAID");
    assertThat(subscriptionStatus(due)).isEqualTo("ACTIVE");
    assertThat(gateway.charges()).hasSize(1);
  }

  @Test
  void 같은_재시도_작업을_여러_작업자가_동시에_실행해도_결제대행사는_한_번만_호출한다() throws Exception {
    Due due = activeDue();
    gateway.respondWith(PaymentStatus.FAILED);
    jobService.runRenewals(Instant.now());
    assertThat(invoiceStatus()).isEqualTo("FAILED");
    gateway.reset();
    gateway.delayCharges(200);
    jdbcTemplate.update("UPDATE billing_invoice SET next_retry_at = now() - interval '1 minute'");

    runConcurrently(
        4,
        () -> {
          jobService.runRetries(Instant.now());
          return null;
        });

    assertThat(gateway.charges()).hasSize(1);
    assertThat(count("SELECT count(*) FROM billing_charge_attempt")).isEqualTo(2);
    assertThat(invoiceStatus()).isEqualTo("PAID");
    assertThat(subscriptionStatus(due)).isEqualTo("ACTIVE");
  }

  @Test
  void 운영자_재실행은_반영_실패로_처리_중인_청구서를_새로_청구하지_않고_이후_조회로_확정된다() {
    Due due = activeDue();
    doThrow(new IllegalStateException("DB 반영 실패"))
        .doCallRealMethod()
        .when(transitions)
        .onPaid(any(), any());
    jobService.runRenewals(Instant.now());
    String key =
        jdbcTemplate.queryForObject(
            "SELECT idempotency_key FROM system_job_run WHERE job_name = 'billing-renewal'",
            String.class);
    assertThat(invoiceStatus()).isEqualTo("PROCESSING");

    Runnable work = jobService.rerunWork("billing-renewal", key, Instant.now()).orElseThrow();
    assertThat(jobRunService.rerunFailed(key, work)).isEqualTo(RerunOutcome.SUCCESS);

    assertThat(gateway.charges()).hasSize(1);
    assertThat(invoiceStatus()).isEqualTo("PROCESSING");
    assertThat(jobRunService.rerunFailed(key, work)).isEqualTo(RerunOutcome.NOT_STARTED);

    jdbcTemplate.update(
        "UPDATE billing_charge_attempt SET attempted_at = ?",
        Timestamp.from(Instant.now().minus(Duration.ofMinutes(20))));
    jobService.recoverInterruptedCharges(Instant.now());
    jobService.runReconciliation();
    assertThat(invoiceStatus()).isEqualTo("PAID");
    assertThat(subscriptionStatus(due)).isEqualTo("ACTIVE");
    assertThat(gateway.charges()).hasSize(1);
  }

  @Test
  void 운영자_재실행은_청구서만_발행된_실패_작업을_한_번만_청구하고_두_번째_재실행은_시작하지_않는다() {
    Due due = activeDue();
    long invoiceId =
        fixtures
            .invoice(due.officeId(), due.subscriptionId(), due.consentId())
            .status("UNBILLED")
            .period(due.periodEnd(), due.periodEnd().plus(Duration.ofDays(30)))
            .insert();
    String key = "renewal:" + due.subscriptionId() + ":" + due.periodEnd().getEpochSecond();
    jdbcTemplate.update(
        "INSERT INTO system_job_run (job_name, office_id, idempotency_key, status, scheduled_at,"
            + " started_at, finished_at, error_code) VALUES ('billing-renewal', ?, ?, 'FAILED', ?,"
            + " now(), now(), 'STALE_RUNNING')",
        due.officeId(),
        key,
        Timestamp.from(due.periodEnd()));

    Runnable work = jobService.rerunWork("billing-renewal", key, Instant.now()).orElseThrow();
    assertThat(jobRunService.rerunFailed(key, work)).isEqualTo(RerunOutcome.SUCCESS);
    assertThat(jobRunService.rerunFailed(key, work)).isEqualTo(RerunOutcome.NOT_STARTED);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM billing_invoice WHERE id = ?", String.class, invoiceId))
        .isEqualTo("PAID");
    assertThat(gateway.charges()).hasSize(1);
    assertThat(subscriptionStatus(due)).isEqualTo("ACTIVE");
  }

  private void closeOffice(Due due) {
    Long membershipId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM office_membership WHERE office_id = ? AND user_id = ?",
            Long.class,
            due.officeId(),
            due.ownerUserId());
    var owner =
        new StaffPrincipal(
            due.ownerUserId(), membershipId, due.officeId(), "시험 대표", StaffRole.OWNER);
    var session = new MockHttpSession();
    stepUpState.grant(session);
    officeCloseService.close(
        owner,
        session,
        new JsonMapper().readTree("{\"confirmed\":true,\"archiveChoice\":\"DECLINE\"}"));
    assertThat(subscriptionStatus(due)).isEqualTo("ENDED");
    assertThat(count("SELECT count(*) FROM office WHERE closed_at IS NOT NULL")).isEqualTo(1);
  }

  @Test
  void 사무소_종료와_승인_조회가_겹쳐도_청구만_승인되고_구독은_종료를_유지한다() throws Exception {
    Due due = activeDue();
    gateway.respondWith(PaymentStatus.VERIFYING);
    jobService.runRenewals(Instant.now());
    assertThat(invoiceStatus()).isEqualTo("VERIFYING");
    gateway.resolve(lastOrderId(), PaymentStatus.SUCCEEDED);
    long invoiceId = count("SELECT id FROM billing_invoice");
    var inquiryStarted = new CountDownLatch(1);
    var officeClosed = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              inquiryStarted.countDown();
              assertThat(officeClosed.await(10, TimeUnit.SECONDS)).isTrue();
              return invocation.callRealMethod();
            })
        .when(gateway)
        .inquire(any());

    var reconciliation = executor.submit(() -> chargeService.reconcile(invoiceId));
    try {
      assertThat(inquiryStarted.await(10, TimeUnit.SECONDS)).isTrue();
      closeOffice(due);
    } finally {
      officeClosed.countDown();
    }

    assertThat(reconciliation.get(10, TimeUnit.SECONDS)).isPresent();
    assertThat(invoiceStatus()).isEqualTo("PAID");
    assertThat(subscriptionStatus(due)).isEqualTo("ENDED");
    assertThat(gateway.charges()).hasSize(1);
  }

  @Test
  void 승인_반영이_종료보다_먼저_잠금을_잡아도_종료는_최신_구독을_끝낸다() throws Exception {
    Instant overdue = daysFromNow(-31);
    Due due = subscriptionIn("PAST_DUE_RESTRICTED", daysFromNow(-61), overdue, overdue);
    long invoiceId =
        fixtures
            .invoice(due.officeId(), due.subscriptionId(), due.consentId())
            .status("FAILED")
            .period(overdue, overdue.plus(Duration.ofDays(30)))
            .insert();
    gateway.respondWith(PaymentStatus.VERIFYING);
    assertThat(chargeService.charge(invoiceId).isVerifying()).isTrue();
    jobService.runDelinquency(Instant.now());
    assertThat(subscriptionStatus(due)).isEqualTo("ENDED");
    gateway.resolve(lastOrderId(), PaymentStatus.SUCCEEDED);

    var applying = new CountDownLatch(1);
    var allowApproval = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              applying.countDown();
              assertThat(allowApproval.await(15, TimeUnit.SECONDS)).isTrue();
              return invocation.callRealMethod();
            })
        .when(transitions)
        .onPaid(any(), any());

    var reconciliation = executor.submit(() -> chargeService.reconcile(invoiceId));
    Future<?> closure;
    try {
      assertThat(applying.await(10, TimeUnit.SECONDS)).isTrue();
      closure = executor.submit(() -> closeOffice(due));
      await()
          .atMost(Duration.ofSeconds(10))
          .until(
              () ->
                  count(
                          "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted"
                              + " AND database = (SELECT oid FROM pg_database WHERE datname = current_database())")
                      > 0);
    } finally {
      allowApproval.countDown();
    }

    assertThat(reconciliation.get(10, TimeUnit.SECONDS)).isPresent();
    closure.get(10, TimeUnit.SECONDS);
    assertThat(invoiceStatus()).isEqualTo("PAID");
    assertThat(subscriptionStatus(due)).isEqualTo("ENDED");
    assertThat(gateway.charges()).hasSize(1);
  }

  @Test
  void 직접_종료된_구독의_중복_승인과_재시도는_청구를_다시_만들지_않는다() throws Exception {
    Due due = activeDue();
    gateway.respondWith(PaymentStatus.VERIFYING);
    jobService.runRenewals(Instant.now());
    assertThat(invoiceStatus()).isEqualTo("VERIFYING");
    closeOffice(due);
    Timestamp endedAt =
        jdbcTemplate.queryForObject(
            "SELECT ended_at FROM subscription WHERE id = ?",
            Timestamp.class,
            due.subscriptionId());
    gateway.resolve(lastOrderId(), PaymentStatus.SUCCEEDED);
    long invoiceId = count("SELECT id FROM billing_invoice");

    var results = runConcurrently(4, () -> chargeService.reconcile(invoiceId));
    assertThat(results).allMatch(Optional.class::isInstance);
    assertThat(results).anyMatch(result -> ((Optional<?>) result).isPresent());
    assertThat(chargeService.reconcile(invoiceId)).isEmpty();
    jobService.runRetries(daysFromNow(31));
    jobService.runRenewals(daysFromNow(31));

    assertThat(invoiceStatus()).isEqualTo("PAID");
    assertThat(subscriptionStatus(due)).isEqualTo("ENDED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT ended_at FROM subscription WHERE id = ?",
                Timestamp.class,
                due.subscriptionId()))
        .isEqualTo(endedAt);
    assertThat(count("SELECT count(*) FROM billing_invoice")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM billing_charge_attempt")).isEqualTo(1);
    assertThat(gateway.charges()).hasSize(1);
  }

  @Test
  void 직접_종료_뒤_중단된_청구를_복구해도_구독은_끝난_상태로_남는다() {
    Due due = activeDue();
    doThrow(new IllegalStateException("승인 기록 반영 중단")).when(transitions).onPaid(any(), any());
    jobService.runRenewals(Instant.now());
    assertThat(invoiceStatus()).isEqualTo("PROCESSING");
    doCallRealMethod().when(transitions).onPaid(any(), any());
    closeOffice(due);

    jdbcTemplate.update(
        "UPDATE billing_charge_attempt SET attempted_at = ?",
        Timestamp.from(Instant.now().minus(Duration.ofMinutes(20))));
    jobService.recoverInterruptedCharges(Instant.now());
    assertThat(invoiceStatus()).isEqualTo("VERIFYING");
    jobService.runReconciliation();

    assertThat(invoiceStatus()).isEqualTo("PAID");
    assertThat(subscriptionStatus(due)).isEqualTo("ENDED");
    assertThat(gateway.charges()).hasSize(1);
  }
}
