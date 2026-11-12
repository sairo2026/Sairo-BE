package com.sairo.be.scenario;

import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.billing.service.BillingJobService;
import com.sairo.be.domain.billing.service.BillingReconciliationService;
import com.sairo.be.domain.billing.service.PriceScheduleSelector;
import com.sairo.be.domain.billing.service.SubscriptionAllocationService;
import com.sairo.be.domain.billing.service.SubscriptionAllocationService.VerificationFacts;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.PaymentGateway.PaymentStatus;
import com.sairo.be.global.port.stub.StubPaymentGateway;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.support.TextCipher;
import com.sairo.be.global.support.TimeRules;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsConsoleTestBase;
import com.sairo.be.support.fixture.BillingFixtures;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.JsonNode;

/**
 * 출시 필수 시나리오 ⑦⑧⑨와 ⑱ 요금 시험 항목 가운데 기존 시험이 덮지 않는 부분을 실제 엔드포인트를 이어 부르는 흐름으로 확인한다. 청구·재시도·대사 배치는 엔드포인트가
 * 없어 작업 서비스를 직접 부르고, 결제대행사는 시험 스텁의 응답 조절만 쓴다.
 */
@IntegrationTest
class LaunchScenarioBillingIntegrationTest extends OpsConsoleTestBase {

  private static final String CURRENT = "/api/subscriptions/current";
  private static final String KEY_SESSION = "/api/subscriptions/billing-key/session";
  private static final String KEY = "/api/subscriptions/billing-key";
  private static final String PREVIEW = "/api/subscriptions/purchase-consent/preview";
  private static final String CONSENT = "/api/subscriptions/purchase-consent";
  private static final String FIRST = "/api/subscriptions/first-payment";
  private static final String RETRY = "/api/subscriptions/retry-payment";
  private static final String INVOICES = "/api/billing/invoices";
  private static final String RECON = "/api/ops/billing/reconciliation";
  private static final String AGREEMENTS = "/api/ops/start-customer-agreements";
  private static final String STANDARD_VERSION = "2026-12-STD";

  @Autowired private StubPaymentGateway paymentGateway;
  @Autowired private SubscriptionAllocationService allocation;
  @Autowired private BillingJobService jobService;
  @Autowired private BillingReconciliationService reconciliationService;
  @Autowired private PriceScheduleSelector priceSelector;

  @Autowired
  @Qualifier("billingKeyCipher") private TextCipher billingKeyCipher;

  private BillingFixtures fixtures;
  private Instant now;

  @BeforeEach
  void seedCatalog() {
    paymentGateway.reset();
    fixtures = new BillingFixtures(jdbcTemplate);
    now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    fixtures.standardPrice(STANDARD_VERSION, now.minus(Duration.ofDays(60)));
    fixtures.purchaseTerms(BillingFixtures.PURCHASE_TERMS_VERSION);
    fixtures.cardNoticeTerms(BillingFixtures.CARD_NOTICE_VERSION);
  }

  private Instant days(long value) {
    return now.plus(Duration.ofDays(value));
  }

  private static Instant kst(String localDateTime) {
    return Instant.parse(localDateTime + "+09:00");
  }

  private record Owner(TestData.Staff staff, Cookie cookie) {

    long officeId() {
      return staff.office().getId();
    }
  }

  private Owner newOwner() {
    TestData.Staff staff = staff(OfficeMembershipRole.OWNER);
    Cookie cookie = memberSession(staff);
    grantStepUp(cookie);
    return new Owner(staff, cookie);
  }

  /** 사무소 인증 완료 때 구독을 배정한다. 운영 경로에 호출 지점이 없어 서비스를 직접 부른다. 사무소 생성·최초 신청 시각은 인증 완료 시각과 같게 둔다. */
  private void allocate(Owner owner, Instant verifiedAt) {
    allocation.allocateOnVerification(
        new VerificationFacts(owner.officeId(), verifiedAt, verifiedAt, verifiedAt));
  }

  private JsonNode json(org.springframework.test.web.servlet.ResultActions actions)
      throws Exception {
    return AuthFlowSupport.json(actions.andReturn());
  }

  private void registerCard(Owner owner) throws Exception {
    String customerKey =
        json(post(KEY_SESSION, "{}", owner.cookie()).andExpect(status().isOk()))
            .get("customerKey")
            .asString();
    post(
            KEY,
            "{\"authKey\":\"auth-key-1\",\"customerKey\":\""
                + customerKey
                + "\",\"cardNoticeVersion\":\""
                + BillingFixtures.CARD_NOTICE_VERSION
                + "\"}",
            owner.cookie())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.registered").value(true));
  }

  private void agreePurchase(Owner owner) throws Exception {
    JsonNode preview =
        json(mockMvc.perform(get(PREVIEW).cookie(owner.cookie())).andExpect(status().isOk()));
    String body =
        "{\"termsVersion\":\""
            + preview.get("termsVersion").asString()
            + "\",\"plan\":\""
            + preview.get("plan").asString()
            + "\",\"memberCount\":"
            + preview.get("memberCount").asInt()
            + ",\"priceVersion\":\""
            + preview.get("priceVersion").asString()
            + "\",\"totalAmountInclVat\":"
            + preview.get("totalAmountInclVat").asLong()
            + ",\"amountLimit\":"
            + preview.get("amountLimit").asLong()
            + ",\"periodStart\":\""
            + preview.get("periodStart").asString()
            + "\",\"periodEnd\":\""
            + preview.get("periodEnd").asString()
            + "\",\"nextChargeAt\":\""
            + preview.get("nextChargeAt").asString()
            + "\",\"agreed\":true}";
    post(CONSENT, body, owner.cookie()).andExpect(status().isOk());
  }

  private Map<String, Object> subscriptionOf(Owner owner) {
    return jdbcTemplate.queryForMap(
        "SELECT * FROM subscription WHERE office_id = ?", owner.officeId());
  }

  private String orderIdOf(Owner owner) {
    return jdbcTemplate.queryForObject(
        "SELECT a.pg_order_id FROM billing_charge_attempt a JOIN billing_invoice i ON i.id ="
            + " a.invoice_id WHERE i.office_id = ? ORDER BY a.id DESC LIMIT 1",
        String.class,
        owner.officeId());
  }

  /** 결제 전 구독에 카드와 구매 동의를 엔드포인트로 준비하고 첫 결제를 요청한다. */
  private Owner paidOffice() throws Exception {
    Owner owner = newOwner();
    allocate(owner, now);
    registerCard(owner);
    agreePurchase(owner);
    post(FIRST, "", owner.cookie())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    return owner;
  }

  @Test
  void 시나리오07_체험_시작_첫_결제_실패로_즉시_종료되고_반환기간에_대표가_직접_재결제하면_구독이_재개된다() throws Exception {
    fixtures.trialEvent("GENERAL", "DURATION", days(-90), days(30), null, "ACTIVE");
    Owner owner = newOwner();
    allocate(owner, days(-59));
    mockMvc
        .perform(get(CURRENT).cookie(owner.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("TRIAL"))
        .andExpect(jsonPath("$.trialEndsAt").exists())
        .andExpect(jsonPath("$.readiness.hasBillingKey").value(false));

    registerCard(owner);
    agreePurchase(owner);
    mockMvc
        .perform(get(CURRENT).cookie(owner.cookie()))
        .andExpect(jsonPath("$.readiness.hasBillingKey").value(true))
        .andExpect(jsonPath("$.readiness.hasValidConsent").value(true));

    // 시간 경과: 무료 기간 끝을 이틀 앞당긴다.
    jdbcTemplate.update(
        "UPDATE subscription SET trial_started_at = trial_started_at - interval '2 days',"
            + " trial_ends_at = trial_ends_at - interval '2 days',"
            + " first_billing_scheduled_at = first_billing_scheduled_at - interval '2 days'"
            + " WHERE office_id = ?",
        owner.officeId());
    paymentGateway.respondWith(PaymentStatus.FAILED);
    jobService.runFirstCharges(Instant.now());

    mockMvc
        .perform(get(CURRENT).cookie(owner.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ENDED"))
        .andExpect(jsonPath("$.isInReturnPeriod").value(true))
        .andExpect(jsonPath("$.returnPeriodEndsAt").exists())
        .andExpect(jsonPath("$.canRetryPayment").value(true))
        .andExpect(jsonPath("$.latestInvoice.status").value("FAILED"));
    assertThat(subscriptionOf(owner).get("ended_at")).isNotNull();
    assertThat(paymentGateway.charges()).hasSize(1);

    paymentGateway.respondWith(PaymentStatus.SUCCEEDED);
    post(RETRY, "", owner.cookie())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.isInReturnPeriod").value(false));
    mockMvc
        .perform(get(INVOICES).cookie(owner.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].status").value("PAID"));
    Map<String, Object> subscription = subscriptionOf(owner);
    assertThat(subscription.get("status")).isEqualTo("ACTIVE");
    assertThat(subscription.get("ended_at")).isNull();
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT result FROM billing_charge_attempt ORDER BY attempt_no", String.class))
        .containsExactly("FAILED", "SUCCESS");
    assertThat(paymentGateway.charges()).hasSize(2);
  }

  private Owner renewalDue() throws Exception {
    Owner owner = paidOffice();
    // 시간 경과: 현재 이용 기간과 그 청구서의 청구기간이 1분 전에 끝난 것으로 당긴다.
    Instant end = Instant.now().minus(Duration.ofMinutes(1));
    Instant start = end.minus(Duration.ofDays(30));
    jdbcTemplate.update(
        "UPDATE subscription SET current_period_start = ?, current_period_end = ?,"
            + " cycle_anchor_at = ? WHERE office_id = ?",
        Timestamp.from(start),
        Timestamp.from(end),
        Timestamp.from(start),
        owner.officeId());
    jdbcTemplate.update(
        "UPDATE billing_invoice SET billing_period_start = ?, billing_period_end = ? WHERE"
            + " office_id = ?",
        Timestamp.from(start),
        Timestamp.from(end),
        owner.officeId());
    return owner;
  }

  private Map<String, Object> renewalInvoice(Owner owner) {
    return jdbcTemplate.queryForMap(
        "SELECT i.id, i.status, a.result, a.pg_order_id, a.idempotency_key FROM billing_invoice i"
            + " JOIN billing_charge_attempt a ON a.invoice_id = i.id WHERE i.office_id = ? AND"
            + " i.id = (SELECT max(id) FROM billing_invoice WHERE office_id = ?)",
        owner.officeId(),
        owner.officeId());
  }

  @Test
  void 시나리오08_갱신_PG_타임아웃은_확인_중으로_두고_같은_주문으로_결과를_확인해_중복_승인_없이_성공한다() throws Exception {
    Owner owner = renewalDue();
    int chargesBefore = paymentGateway.charges().size();
    paymentGateway.throwOnNextCharge(new IllegalStateException("timeout"));
    jobService.runRenewals(Instant.now());

    Map<String, Object> verifying = renewalInvoice(owner);
    assertThat(verifying.get("status")).isEqualTo("VERIFYING");
    assertThat(verifying.get("result")).isEqualTo("VERIFYING");
    mockMvc
        .perform(get(INVOICES).cookie(owner.cookie()))
        .andExpect(jsonPath("$.items[*].status", hasItem("VERIFYING")));
    mockMvc
        .perform(get(CURRENT).cookie(owner.cookie()))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.latestInvoice.status").value("VERIFYING"))
        .andExpect(jsonPath("$.canRetryPayment").value(false));

    jobService.runRenewals(Instant.now());
    jobService.runRetries(Instant.now());
    jobService.runReconciliation();
    assertThat(renewalInvoice(owner).get("status")).isEqualTo("VERIFYING");
    assertThat(paymentGateway.charges()).hasSize(chargesBefore + 1);

    paymentGateway.resolve((String) verifying.get("pg_order_id"), PaymentStatus.SUCCEEDED);
    jobService.runReconciliation();
    jobService.runReconciliation();

    Map<String, Object> paid = renewalInvoice(owner);
    assertThat(paid.get("status")).isEqualTo("PAID");
    assertThat(paid.get("result")).isEqualTo("SUCCESS");
    assertThat(paid.get("id")).isEqualTo(verifying.get("id"));
    assertThat(paid.get("pg_order_id")).isEqualTo(verifying.get("pg_order_id"));
    assertThat(paid.get("idempotency_key")).isEqualTo(verifying.get("idempotency_key"));
    assertThat(
            count(
                "SELECT count(*) FROM billing_charge_attempt WHERE invoice_id = ?",
                verifying.get("id")))
        .isEqualTo(1);
    assertThat(paymentGateway.charges()).hasSize(chargesBefore + 1);
    mockMvc
        .perform(get(CURRENT).cookie(owner.cookie()))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.latestInvoice.status").value("PAID"));
    assertThat(((Timestamp) subscriptionOf(owner).get("current_period_end")).toInstant())
        .isAfter(Instant.now());
  }

  @Test
  void 시나리오08_확인_중인_갱신_결제가_PG에서_실패로_확정되면_미납_유예로_바꾸고_새_결제를_만들지_않는다() throws Exception {
    Owner owner = renewalDue();
    int chargesBefore = paymentGateway.charges().size();
    paymentGateway.throwOnNextCharge(new IllegalStateException("timeout"));
    jobService.runRenewals(Instant.now());
    Map<String, Object> verifying = renewalInvoice(owner);

    paymentGateway.resolve((String) verifying.get("pg_order_id"), PaymentStatus.FAILED);
    jobService.runReconciliation();

    Map<String, Object> failed = renewalInvoice(owner);
    assertThat(failed.get("status")).isEqualTo("FAILED");
    assertThat(failed.get("result")).isEqualTo("FAILED");
    assertThat(failed.get("pg_order_id")).isEqualTo(verifying.get("pg_order_id"));
    assertThat(paymentGateway.charges()).hasSize(chargesBefore + 1);
    mockMvc
        .perform(get(CURRENT).cookie(owner.cookie()))
        .andExpect(jsonPath("$.status").value("PAST_DUE_GRACE"))
        .andExpect(jsonPath("$.latestInvoice.status").value("FAILED"))
        .andExpect(jsonPath("$.canRetryPayment").value(true));
  }

  @Test
  void 시나리오09_PG_조회_대사는_다섯_분류를_만들고_결제담당이_근거를_남겨_차이를_해결한다() throws Exception {
    Owner matched = paidOffice();
    Owner mismatched = paidOffice();
    Owner internalOnly = paidOffice();
    Owner verifying = paidOffice();
    Owner pgOnly = newOwner();
    allocate(pgOnly, now);
    registerCard(pgOnly);
    agreePurchase(pgOnly);
    paymentGateway.respondWith(PaymentStatus.FAILED);
    assertError(mockMvc.perform(jsonPost(FIRST, "", pgOnly.cookie())), ErrorCode.PAYMENT_FAILED);
    paymentGateway.respondWith(PaymentStatus.SUCCEEDED);

    String mismatchOrder = orderIdOf(mismatched);
    paymentGateway.recordApproval(mismatchOrder, "stub-payment-" + mismatchOrder, 36_900);
    paymentGateway.resolve(orderIdOf(internalOnly), PaymentStatus.FAILED);
    paymentGateway.resolve(orderIdOf(verifying), PaymentStatus.VERIFYING);
    String pgOnlyOrder = orderIdOf(pgOnly);
    paymentGateway.recordApproval(pgOnlyOrder, "stub-payment-" + pgOnlyOrder, 39_900);

    LocalDate businessDate = TimeRules.displayDate(Instant.now());
    assertThat(reconciliationService.run(businessDate)).isTrue();
    assertThat(reconciliationService.run(businessDate)).isFalse();

    Operator billing = operator("박결제", OperatorRole.BILLING);
    Cookie billingSession = sessionOf(billing);
    mockMvc
        .perform(get(RECON).param("businessDate", businessDate.toString()).cookie(billingSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(5))
        .andExpect(
            jsonPath(
                "$.items[*].status",
                containsInAnyOrder(
                    "MATCHED", "AMOUNT_MISMATCH", "INTERNAL_ONLY", "VERIFYING", "PG_ONLY")));
    assertThat(statusOfEntry(matched)).isEqualTo("MATCHED");
    assertThat(statusOfEntry(mismatched)).isEqualTo("AMOUNT_MISMATCH");
    assertThat(statusOfEntry(internalOnly)).isEqualTo("INTERNAL_ONLY");
    assertThat(statusOfEntry(verifying)).isEqualTo("VERIFYING");
    assertThat(statusOfEntry(pgOnly)).isEqualTo("PG_ONLY");
    assertThat(count("SELECT count(*) FROM billing_invoice WHERE office_id = ?", pgOnly.officeId()))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM billing_invoice WHERE office_id = ?",
                String.class,
                pgOnly.officeId()))
        .isEqualTo("FAILED");

    long mismatchEntry = entryOf(mismatched);
    post(
            RECON + "/" + mismatchEntry + "/resolve",
            "{\"differenceReason\":\"PG 승인액 정정 대기\",\"resolutionEvidence\":\"PG 거래 상세 대조\"}",
            billingSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("AMOUNT_MISMATCH"))
        .andExpect(jsonPath("$.resolvedAt").isNotEmpty());
    mockMvc
        .perform(
            get(RECON)
                .param("businessDate", businessDate.toString())
                .param("unresolved", "true")
                .cookie(billingSession))
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(
            jsonPath(
                "$.items[*].status", containsInAnyOrder("INTERNAL_ONLY", "VERIFYING", "PG_ONLY")));
    assertThat(auditCount("RECONCILIATION_RESOLVED")).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT status FROM billing_invoice WHERE office_id IN (?, ?) ORDER BY id",
                String.class,
                internalOnly.officeId(),
                verifying.officeId()))
        .containsExactly("PAID", "PAID");
  }

  private long entryOf(Owner owner) {
    return jdbcTemplate.queryForObject(
        "SELECT id FROM billing_reconciliation_entry WHERE office_id = ?",
        Long.class,
        owner.officeId());
  }

  private String statusOfEntry(Owner owner) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM billing_reconciliation_entry WHERE office_id = ?",
        String.class,
        owner.officeId());
  }

  @Test
  void 시나리오18_이벤트_선택은_가입_창_시작_시각에_정확히_인증한_사무소를_그_이벤트에_넣고_직전_1초는_앞_이벤트에_넣는다() {
    Instant feb = kst("2027-02-01T00:00:00");
    Instant mar = kst("2027-03-02T00:00:00");
    Instant apr = kst("2027-04-01T00:00:00");
    long febEvent = fixtures.trialEvent("GENERAL", "DURATION", feb, mar, null, "ACTIVE");
    long marEvent = fixtures.trialEvent("GENERAL", "DURATION", mar, apr, null, "ACTIVE");
    long aprEvent =
        fixtures.trialEvent("GENERAL", "DURATION", apr, kst("2027-12-31T00:00:00"), null, "ACTIVE");
    Map<Instant, Long> expected = new java.util.LinkedHashMap<>();
    expected.put(feb.minusSeconds(1), null);
    expected.put(feb, febEvent);
    expected.put(mar.minusSeconds(1), febEvent);
    expected.put(mar, marEvent);
    expected.put(apr.minusSeconds(1), marEvent);
    expected.put(apr, aprEvent);
    expected.forEach(
        (verifiedAt, eventId) -> {
          Owner owner = newOwner();
          allocate(owner, verifiedAt);
          Map<String, Object> subscription = subscriptionOf(owner);
          assertThat(subscription.get("trial_event_id"))
              .as("인증 완료 %s", verifiedAt)
              .isEqualTo(eventId);
          assertThat(subscription.get("status"))
              .as("인증 완료 %s", verifiedAt)
              .isEqualTo(eventId == null ? "PRE_PAYMENT" : "TRIAL");
        });
  }

  private long startCustomerEvent() {
    return fixtures.trialEvent(
        "START_CUSTOMER", "FIXED_END", days(-1), days(30), kst("2027-07-01T00:00:00"), "ACTIVE");
  }

  private String agreementBody(long officeId, long eventId, long priceId) {
    return "{\"officeId\":"
        + officeId
        + ",\"trialEventId\":"
        + eventId
        + ",\"priceScheduleId\":"
        + priceId
        + ",\"contractedOn\":\""
        + TimeRules.displayDate(Instant.now())
        + "\",\"contractReference\":\"SC-1\",\"weeklyUseConfirmed\":true,"
        + "\"monthlyInterviewConfirmed\":true,\"agreementTextVersion\":\"SC-2026-11\"}";
  }

  private long register(Cookie ops, long officeId, long eventId, long priceId) throws Exception {
    return json(post(AGREEMENTS, agreementBody(officeId, eventId, priceId), ops)
            .andExpect(status().isCreated()))
        .get("agreementId")
        .longValue();
  }

  private int approve(Cookie billing, long agreementId, String version) throws Exception {
    return mockMvc
        .perform(
            jsonPost(AGREEMENTS + "/" + agreementId + "/approve", "", billing)
                .header("If-Match", version))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Test
  void 시나리오18_시작_고객은_동시에_15번째를_노리면_하나만_승인되고_16번째는_409이며_합의가_있으면_재등록을_막고_취소_뒤에는_받는다()
      throws Exception {
    long event = startCustomerEvent();
    long price = fixtures.price("2027-07-START", "START_CUSTOMER", days(-1), "ACTIVE", 29_900);
    for (int i = 0; i < 14; i++) {
      fixtures.startCustomerAgreement(
          staff(OfficeMembershipRole.OWNER).office().getId(),
          event,
          price,
          kst("2027-07-01T00:00:00"),
          kst("2028-07-01T00:00:00"),
          "APPROVED");
    }
    Cookie ops = sessionOf(operator("최운영", OperatorRole.OPS));
    Cookie billing = sessionOf(operator("박결제", OperatorRole.BILLING));
    long firstOffice = staff(OfficeMembershipRole.OWNER).office().getId();
    long secondOffice = staff(OfficeMembershipRole.OWNER).office().getId();
    long first = register(ops, firstOffice, event, price);
    long second = register(ops, secondOffice, event, price);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> futures = new ArrayList<>();
    for (long agreement : new long[] {first, second}) {
      futures.add(
          pool.submit(
              () -> {
                start.await();
                return approve(billing, agreement, "1");
              }));
    }
    start.countDown();
    List<Integer> codes = new ArrayList<>();
    for (Future<Integer> future : futures) {
      codes.add(future.get());
    }
    pool.shutdown();
    assertThat(codes).containsExactlyInAnyOrder(200, 409);
    assertThat(
            count(
                "SELECT count(*) FROM billing_start_customer_agreement WHERE trial_event_id = ?"
                    + " AND status = 'APPROVED'",
                event))
        .isEqualTo(15);
    long winner = codes.get(0) == 200 ? first : second;
    long winnerOffice = winner == first ? firstOffice : secondOffice;
    long loser = winner == first ? second : first;
    assertError(
        mockMvc.perform(
            jsonPost(AGREEMENTS + "/" + loser + "/approve", "", billing).header("If-Match", "1")),
        ErrorCode.START_CUSTOMER_QUOTA_EXCEEDED);
    long sixteenth =
        register(ops, staff(OfficeMembershipRole.OWNER).office().getId(), event, price);
    assertError(
        mockMvc.perform(
            jsonPost(AGREEMENTS + "/" + sixteenth + "/approve", "", billing)
                .header("If-Match", "1")),
        ErrorCode.START_CUSTOMER_QUOTA_EXCEEDED);

    assertError(
        post(AGREEMENTS, agreementBody(winnerOffice, event, price), ops),
        ErrorCode.START_CUSTOMER_AGREEMENT_EXISTS);
    mockMvc
        .perform(
            jsonPost(AGREEMENTS + "/" + winner + "/revoke", "{\"reason\":\"합의서 체결 철회\"}", billing)
                .header("If-Match", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REVOKED"));
    post(AGREEMENTS, agreementBody(winnerOffice, event, price), ops)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("REGISTERED"));
  }

  @Test
  void 시나리오18_시작_고객의_첫_청구는_2027년_7월_1일_전에는_없고_그_시각에_한_달_청구기간과_시작_고객_가격으로_만들어진다() throws Exception {
    Instant july = kst("2027-07-01T00:00:00");
    Instant guaranteeEnd = kst("2028-07-01T00:00:00");
    long event = startCustomerEvent();
    long price = fixtures.price("2027-07-START", "START_CUSTOMER", days(-1), "ACTIVE", 29_900);
    Owner owner = newOwner();
    fixtures.startCustomerAgreement(owner.officeId(), event, price, july, guaranteeEnd, "APPROVED");
    allocate(owner, Instant.now());
    Map<String, Object> trial = subscriptionOf(owner);
    assertThat(trial.get("status")).isEqualTo("TRIAL");
    assertThat(((Timestamp) trial.get("first_billing_scheduled_at")).toInstant()).isEqualTo(july);
    long subscriptionId = (Long) trial.get("id");
    fixtures.consent(
        subscriptionId, owner.staff().user().getId(), owner.officeId(), 29_900, 2_718, 39_900);
    fixtures.billingCustomerWithKey(
        owner.officeId(), "customer-" + owner.officeId(), billingKeyCipher.encrypt("key"));

    jobService.runFirstCharges(kst("2026-12-15T12:00:00"));
    jobService.runFirstCharges(july.minusSeconds(1));
    assertThat(count("SELECT count(*) FROM billing_invoice")).isZero();
    assertThat(paymentGateway.charges()).isEmpty();

    jobService.runFirstCharges(july);
    Map<String, Object> invoice =
        jdbcTemplate.queryForMap(
            "SELECT status, amount, price_version, billing_period_start, billing_period_end"
                + " FROM billing_invoice");
    assertThat(invoice.get("status")).isEqualTo("PAID");
    assertThat(invoice.get("amount")).isEqualTo(29_900L);
    assertThat(invoice.get("price_version")).isEqualTo("2027-07-START");
    assertThat(((Timestamp) invoice.get("billing_period_start")).toInstant()).isEqualTo(july);
    assertThat(((Timestamp) invoice.get("billing_period_end")).toInstant())
        .isEqualTo(kst("2027-08-01T00:00:00"));
    Map<String, Object> active = subscriptionOf(owner);
    assertThat(active.get("status")).isEqualTo("ACTIVE");
    assertThat(((Timestamp) active.get("cycle_anchor_at")).toInstant()).isEqualTo(july);

    var lastGuaranteed = priceSelector.find(owner.officeId(), guaranteeEnd.minusSeconds(1));
    assertThat(lastGuaranteed).isPresent();
    assertThat(lastGuaranteed.get().startCustomer()).isTrue();
    assertThat(lastGuaranteed.get().version()).isEqualTo("2027-07-START");
    var afterGuarantee = priceSelector.find(owner.officeId(), guaranteeEnd);
    assertThat(afterGuarantee).isPresent();
    assertThat(afterGuarantee.get().startCustomer()).isFalse();
    assertThat(afterGuarantee.get().version()).isEqualTo(STANDARD_VERSION);
  }

  private long activeDueSubscription(TestData.Staff owner, Instant periodEnd) {
    long subscriptionId =
        fixtures
            .subscription(owner.office().getId())
            .status("ACTIVE")
            .period(periodEnd.minus(Duration.ofDays(30)), periodEnd)
            .insert();
    fixtures.consent(
        subscriptionId, owner.user().getId(), owner.office().getId(), 39_900, 3_627, 39_900);
    fixtures.billingCustomerWithKey(
        owner.office().getId(),
        "customer-" + owner.office().getId(),
        billingKeyCipher.encrypt("key"));
    return subscriptionId;
  }

  private long standardPriceId() {
    return jdbcTemplate.queryForObject(
        "SELECT id FROM price_schedule WHERE version = ?", Long.class, STANDARD_VERSION);
  }

  private void insertNotice(
      long officeId,
      long subscriptionId,
      long fromPrice,
      long toPrice,
      Instant notifiedAt,
      Instant effectiveAt) {
    jdbcTemplate.update(
        "INSERT INTO pricing_change_notice (office_id, subscription_id, from_price_schedule_id,"
            + " to_price_schedule_id, change_kind, member_count_snapshot, current_amount_incl_vat,"
            + " new_amount_incl_vat, notified_at, effective_at, applies_from_period_start,"
            + " response_due_at) VALUES (?, ?, ?, ?, 'INCREASE', 1, 39900, 49900, ?, ?, ?, ?)",
        officeId,
        subscriptionId,
        fromPrice,
        toPrice,
        Timestamp.from(notifiedAt),
        Timestamp.from(effectiveAt),
        Timestamp.from(effectiveAt),
        Timestamp.from(effectiveAt));
  }

  @Test
  void 시나리오18_인상_안내는_30일_뒤_정각부터_허용되고_인하는_동의_없이_다음_청구기간에_적용되며_무동의_종료는_환불이_없다() {
    long standard = standardPriceId();
    long higher = fixtures.price("2027-HIGH", "STANDARD", days(40), "SCHEDULED", 49_900);
    TestData.Staff noticeOwner = staff(OfficeMembershipRole.OWNER);
    long noticeSubscription = activeDueSubscription(noticeOwner, days(20));
    Instant notifiedAt = now.minus(Duration.ofDays(1));
    insertNotice(
        noticeOwner.office().getId(),
        noticeSubscription,
        standard,
        higher,
        notifiedAt,
        notifiedAt.plus(Duration.ofDays(30)));
    assertThatThrownBy(
            () ->
                insertNotice(
                    noticeOwner.office().getId(),
                    noticeSubscription,
                    standard,
                    higher,
                    notifiedAt,
                    notifiedAt.plus(Duration.ofDays(30)).minusSeconds(1)))
        .isInstanceOf(DataIntegrityViolationException.class);

    Instant periodEnd = now.minus(Duration.ofMinutes(1));
    TestData.Staff lowerOwner = staff(OfficeMembershipRole.OWNER);
    long lowerSubscription = activeDueSubscription(lowerOwner, periodEnd);
    long lower = fixtures.price("2027-LOW", "STANDARD", days(-1), "ACTIVE", 29_900);
    fixtures
        .notice(lowerOwner.office().getId(), lowerSubscription, standard, lower)
        .nonIncrease()
        .dueAt(periodEnd)
        .insert();
    long consentsBefore =
        count(
            "SELECT count(*) FROM subscription_purchase_consent WHERE subscription_id = ?",
            lowerSubscription);

    TestData.Staff declinedOwner = staff(OfficeMembershipRole.OWNER);
    long declinedSubscription = activeDueSubscription(declinedOwner, periodEnd);
    long increased = fixtures.price("2027-UP", "STANDARD", days(-2), "ENDED", 49_900);
    long declinedNotice =
        fixtures
            .notice(declinedOwner.office().getId(), declinedSubscription, standard, increased)
            .dueAt(periodEnd)
            .insert();

    jobService.runRenewals(Instant.now());

    Map<String, Object> lowered =
        jdbcTemplate.queryForMap(
            "SELECT status, amount, price_version FROM billing_invoice WHERE subscription_id = ?",
            lowerSubscription);
    assertThat(lowered.get("status")).isEqualTo("PAID");
    assertThat(lowered.get("amount")).isEqualTo(29_900L);
    assertThat(lowered.get("price_version")).isEqualTo("2027-LOW");
    assertThat(
            count(
                "SELECT count(*) FROM subscription_purchase_consent WHERE subscription_id = ?",
                lowerSubscription))
        .isEqualTo(consentsBefore);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM subscription WHERE id = ?", String.class, declinedSubscription))
        .isEqualTo("ENDED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT decision FROM pricing_change_notice WHERE id = ?",
                String.class,
                declinedNotice))
        .isEqualTo("ENDED_NO_CONSENT");
    assertThat(
            count(
                "SELECT count(*) FROM billing_invoice WHERE subscription_id = ?",
                declinedSubscription))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM refund_request WHERE office_id = ?",
                declinedOwner.office().getId()))
        .isZero();
  }
}
