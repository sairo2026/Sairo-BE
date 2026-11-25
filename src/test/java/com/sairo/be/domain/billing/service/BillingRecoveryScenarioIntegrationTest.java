package com.sairo.be.domain.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.port.PaymentGateway.PaymentResult;
import com.sairo.be.global.port.PaymentGateway.PaymentStatus;
import com.sairo.be.global.port.stub.StubPaymentGateway;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 청구 복구 시나리오 중 기존 시험이 덮지 않는 조합이다. 결제대행사 승인 뒤 DB 반영 실패, 같은 청구서에 대한 동시 청구, 여러 작업자의 동시 스케줄러 실행, 동시 회수와
 * 동시 결과 확인을 검증한다. 결제대행사는 모의 구현만 쓴다.
 */
@IntegrationTest
class BillingRecoveryScenarioIntegrationTest extends AuthIntegrationTestBase {

  @Autowired private BillingJobService jobService;
  @Autowired private BillingChargeService chargeService;
  @MockitoSpyBean private StubPaymentGateway gateway;
  @Autowired private OfficeRepository officeRepository;
  @Autowired private OfficeMembershipRepository officeMembershipRepository;

  @MockitoSpyBean private SubscriptionTransitions transitions;

  @Autowired
  @Qualifier("billingKeyCipher") private TextCipher cipher;

  private BillingFixtures fixtures;
  private ExecutorService executor;

  @BeforeEach
  void seed() {
    fixtures = new BillingFixtures(jdbcTemplate);
    gateway.reset();
    fixtures.standardPrice("2026-12-STD-R", Instant.now().minus(400, ChronoUnit.DAYS));
    fixtures.purchaseTerms(BillingFixtures.PURCHASE_TERMS_VERSION);
    executor = Executors.newFixedThreadPool(6);
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

  private Due activeDue() {
    TestData.Staff owner =
        new TestData(appUserRepository, officeRepository, officeMembershipRepository)
            .approvedStaff(OfficeMembershipRole.OWNER);
    Instant periodEnd = daysFromNow(-1);
    long subscriptionId =
        fixtures
            .subscription(owner.office().getId())
            .status("ACTIVE")
            .period(daysFromNow(-31), periodEnd)
            .insert();
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

  private long count(String sql) {
    return jdbcTemplate.queryForObject(sql, Long.class);
  }

  private String invoiceStatus() {
    return jdbcTemplate.queryForObject("SELECT status FROM billing_invoice", String.class);
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

  private void ageInFlight(Duration age) {
    jdbcTemplate.update(
        "UPDATE billing_charge_attempt SET attempted_at = ?",
        Timestamp.from(Instant.now().minus(age)));
  }

  @Test
  void 승인_응답의_매출전표_링크는_성공_시도에_저장된다() {
    Due due = activeDue();
    String receipt = "https://dashboard.tosspayments.com/sales-slip?transactionId=example";
    doAnswer(
            invocation -> {
              PaymentResult result = (PaymentResult) invocation.callRealMethod();
              return new PaymentResult(
                  result.status(), result.paymentKey(), result.failureCode(), receipt);
            })
        .when(gateway)
        .charge(any());

    jobService.runRenewals(Instant.now());

    String stored =
        jdbcTemplate.queryForObject(
            "SELECT a.pg_receipt_url FROM billing_charge_attempt a JOIN billing_invoice i ON"
                + " a.invoice_id = i.id WHERE i.subscription_id = ?",
            String.class,
            due.subscriptionId());
    assertThat(stored).isEqualTo(receipt);
  }

  @Test
  void 승인_응답이_유실돼도_조회_결과의_매출전표_링크를_저장한다() {
    Due due = activeDue();
    String receipt = "https://dashboard.tosspayments.com/sales-slip?transactionId=recovered";
    gateway.respondWith(PaymentStatus.VERIFYING);
    jobService.runRenewals(Instant.now());
    long invoiceId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM billing_invoice WHERE subscription_id = ?",
            Long.class,
            due.subscriptionId());
    String orderId =
        jdbcTemplate.queryForObject(
            "SELECT pg_order_id FROM billing_charge_attempt WHERE invoice_id = ?",
            String.class,
            invoiceId);
    gateway.resolve(orderId, PaymentStatus.SUCCEEDED);
    doAnswer(
            invocation -> {
              PaymentResult result = (PaymentResult) invocation.callRealMethod();
              return new PaymentResult(
                  result.status(), result.paymentKey(), result.failureCode(), receipt);
            })
        .when(gateway)
        .inquire(any());

    chargeService.reconcile(invoiceId);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT pg_receipt_url FROM billing_charge_attempt WHERE invoice_id = ?",
                String.class,
                invoiceId))
        .isEqualTo(receipt);
  }

  @Test
  void 결제대행사_승인_뒤_DB_반영이_실패하면_처리_중으로_남고_회수와_조회로_한_번만_반영된다() {
    Due due = activeDue();
    doThrow(new IllegalStateException("DB 반영 실패"))
        .doCallRealMethod()
        .when(transitions)
        .onPaid(any(), any());

    jobService.runRenewals(Instant.now());

    assertThat(gateway.charges()).hasSize(1);
    assertThat(invoiceStatus()).isEqualTo("PROCESSING");
    assertThat(
            jdbcTemplate.queryForObject("SELECT result FROM billing_charge_attempt", String.class))
        .isEqualTo("PROCESSING");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM system_job_run WHERE job_name = 'billing-renewal'",
                String.class))
        .isEqualTo("FAILED");

    jobService.runRenewals(Instant.now());
    assertThat(gateway.charges()).hasSize(1);
    assertThat(invoiceStatus()).isEqualTo("PROCESSING");

    ageInFlight(Duration.ofMinutes(20));
    jobService.recoverInterruptedCharges(Instant.now());
    assertThat(invoiceStatus()).isEqualTo("VERIFYING");
    jobService.runReconciliation();

    assertThat(invoiceStatus()).isEqualTo("PAID");
    assertThat(gateway.charges()).hasSize(1);
    assertThat(count("SELECT count(*) FROM billing_charge_attempt")).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM subscription WHERE id = ?", String.class, due.subscriptionId()))
        .isEqualTo("ACTIVE");
  }

  @Test
  void 같은_청구서를_여러_작업자가_동시에_청구해도_결제대행사는_한_번만_호출한다() throws Exception {
    Due due = activeDue();
    long invoiceId =
        fixtures
            .invoice(due.officeId(), due.subscriptionId(), due.consentId())
            .status("UNBILLED")
            .period(due.periodEnd(), due.periodEnd().plus(Duration.ofDays(30)))
            .insert();
    gateway.delayCharges(300);

    List<Object> results = runConcurrently(6, () -> chargeService.charge(invoiceId));

    long succeeded =
        results.stream().filter(r -> r instanceof BillingChargeService.ChargeOutcome).count();
    long rejected = results.stream().filter(r -> r instanceof BusinessException).count();
    assertThat(succeeded).isEqualTo(1);
    assertThat(rejected).isEqualTo(5);
    assertThat(gateway.charges()).hasSize(1);
    assertThat(count("SELECT count(*) FROM billing_charge_attempt")).isEqualTo(1);
    assertThat(invoiceStatus()).isEqualTo("PAID");
  }

  @Test
  void 스케줄러_잠금을_우회한_여러_작업자의_동시_갱신_실행은_청구서와_결제를_하나만_만든다() throws Exception {
    activeDue();
    gateway.delayCharges(200);

    runConcurrently(
        4,
        () -> {
          jobService.runRenewals(Instant.now());
          return null;
        });

    assertThat(gateway.charges()).hasSize(1);
    assertThat(count("SELECT count(*) FROM billing_invoice")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM billing_charge_attempt")).isEqualTo(1);
    assertThat(invoiceStatus()).isEqualTo("PAID");
    assertThat(count("SELECT count(*) FROM system_job_run WHERE job_name = 'billing-renewal'"))
        .isEqualTo(1);
  }

  private long verifyingFailure(Due due) {
    gateway.respondWith(PaymentStatus.VERIFYING);
    jobService.runRenewals(Instant.now());
    long invoiceId =
        count("SELECT id FROM billing_invoice WHERE subscription_id = " + due.subscriptionId());
    String orderId =
        jdbcTemplate.queryForObject(
            "SELECT pg_order_id FROM billing_charge_attempt WHERE invoice_id = ?",
            String.class,
            invoiceId);
    gateway.resolve(orderId, PaymentStatus.FAILED);
    return invoiceId;
  }

  private int failureNotices(Due due) {
    return jdbcTemplate
        .queryForList(
            "SELECT message FROM notification WHERE recipient_user_id = ? AND notification_type ="
                + " 'PAYMENT'",
            String.class,
            due.ownerUserId())
        .size();
  }

  @Test
  void 동시_결과_확인이_실패로_확정돼도_결제_실패_안내는_순차_확인과_같은_수만_나간다() throws Exception {
    Due sequential = activeDue();
    long first = verifyingFailure(sequential);
    chargeService.reconcile(first);
    int expected = failureNotices(sequential);
    assertThat(expected).isPositive();

    Due concurrent = activeDue();
    long second = verifyingFailure(concurrent);
    CyclicBarrier inquired = new CyclicBarrier(2);
    doAnswer(
            invocation -> {
              Object result = invocation.callRealMethod();
              inquired.await(10, TimeUnit.SECONDS);
              return result;
            })
        .when(gateway)
        .inquire(any());

    runConcurrently(2, () -> chargeService.reconcile(second));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM billing_invoice WHERE id = ?", String.class, second))
        .isEqualTo("FAILED");
    assertThat(failureNotices(concurrent)).isEqualTo(expected);
  }

  @Test
  void 동시_회수와_동시_결과_확인은_한_번만_확정하고_구독_기간을_한_번만_늘린다() throws Exception {
    Due due = activeDue();
    gateway.respondWith(PaymentStatus.VERIFYING);
    jobService.runRenewals(Instant.now());
    String orderId =
        jdbcTemplate.queryForObject("SELECT pg_order_id FROM billing_charge_attempt", String.class);
    jdbcTemplate.update("UPDATE billing_invoice SET status = 'PROCESSING'");
    jdbcTemplate.update("UPDATE billing_charge_attempt SET result = 'PROCESSING'");
    ageInFlight(Duration.ofHours(2));
    gateway.resolve(orderId, PaymentStatus.SUCCEEDED);

    AtomicInteger recovered = new AtomicInteger();
    runConcurrently(
        4, () -> recovered.addAndGet(chargeService.recoverStaleProcessing(Instant.now())));
    assertThat(recovered.get()).isEqualTo(1);

    runConcurrently(
        4,
        () ->
            chargeService.reconcile(
                jdbcTemplate.queryForObject("SELECT id FROM billing_invoice", Long.class)));

    assertThat(invoiceStatus()).isEqualTo("PAID");
    assertThat(count("SELECT count(*) FROM billing_charge_attempt")).isEqualTo(1);
    assertThat(gateway.charges()).hasSize(1);
    Instant periodEnd =
        jdbcTemplate
            .queryForObject(
                "SELECT current_period_end FROM subscription WHERE id = ?",
                Timestamp.class,
                due.subscriptionId())
            .toInstant();
    assertThat(periodEnd)
        .isEqualTo(
            BillingJobService.nextBoundary(
                jdbcTemplate
                    .queryForObject(
                        "SELECT cycle_anchor_at FROM subscription WHERE id = ?",
                        Timestamp.class,
                        due.subscriptionId())
                    .toInstant(),
                due.periodEnd()));
  }
}
