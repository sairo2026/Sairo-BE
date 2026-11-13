package com.sairo.be.domain.ops.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.billing.service.PlanNoticeJobService;
import com.sairo.be.domain.billing.service.PriceScheduleSelector;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.support.TimeRules;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsConsoleTestBase;
import com.sairo.be.support.fixture.BillingFixtures;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class OpsPricingIntegrationTest extends OpsConsoleTestBase {

  private static final String AGREEMENTS = "/api/ops/start-customer-agreements";
  private static final String NOTICES = "/api/ops/price-change-notices";

  @Autowired private PlanNoticeJobService planNoticeJobs;
  @Autowired private PriceScheduleSelector selector;

  private BillingFixtures fixtures;
  private Operator ops;
  private Cookie opsSession;
  private Operator billing;
  private Cookie billingSession;
  private Instant now;

  @BeforeEach
  void setUp() {
    fixtures = new BillingFixtures(jdbcTemplate);
    fixtures.purchaseTerms(BillingFixtures.PURCHASE_TERMS_VERSION);
    ops = operator("최운영", OperatorRole.OPS);
    opsSession = sessionOf(ops);
    billing = operator("박결제", OperatorRole.BILLING);
    billingSession = sessionOf(billing);
    now = Instant.now();
  }

  private Instant days(long value) {
    return now.plus(Duration.ofDays(value));
  }

  private long startEvent() {
    return fixtures.trialEvent(
        "START_CUSTOMER", "FIXED_END", days(-1), days(30), days(60), "ACTIVE");
  }

  private long startPrice(String version) {
    return fixtures.price(version, "START_CUSTOMER", days(60), "SCHEDULED", 29_900);
  }

  private String createBody(long officeId, long eventId, long priceId, LocalDate contractedOn) {
    return "{\"officeId\":"
        + officeId
        + ",\"trialEventId\":"
        + eventId
        + ",\"priceScheduleId\":"
        + priceId
        + ",\"contractedOn\":\""
        + contractedOn
        + "\",\"contractReference\":\"SC-1\",\"weeklyUseConfirmed\":true,"
        + "\"monthlyInterviewConfirmed\":true,\"agreementTextVersion\":\"SC-2026-11\"}";
  }

  private long createAgreement(long officeId, long eventId, long priceId) throws Exception {
    String body =
        post(
                AGREEMENTS,
                createBody(officeId, eventId, priceId, TimeRules.displayDate(now)),
                opsSession)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return Long.parseLong(body.replaceAll("(?s).*\"agreementId\":(\\d+).*", "$1"));
  }

  private ResultActions postIfMatch(String path, String body, Cookie cookie, String version)
      throws Exception {
    var request = jsonPost(path, body, cookie);
    return mockMvc.perform(version == null ? request : request.header("If-Match", version));
  }

  private long planChanges(TestData.Staff owner) {
    return count(
        "SELECT count(*) FROM notification WHERE recipient_user_id = ? AND notification_type ="
            + " 'PLAN_CHANGED'",
        owner.user().getId());
  }

  private long activeSubscription(TestData.Staff owner) {
    return fixtures
        .subscription(owner.office().getId())
        .status("ACTIVE")
        .period(days(-5), days(25))
        .insert();
  }

  @Test
  void 새_엔드포인트는_세션이_없으면_401이고_역할이_맞지_않으면_403이다() throws Exception {
    Cookie support = sessionOf(operator("김지원", OperatorRole.SUPPORT));
    for (String path : new String[] {AGREEMENTS, NOTICES}) {
      assertError(mockMvc.perform(get(path)), ErrorCode.UNAUTHENTICATED);
      assertError(mockMvc.perform(get(path).cookie(support)), ErrorCode.FORBIDDEN);
    }
    for (String path :
        new String[] {
          AGREEMENTS,
          AGREEMENTS + "/1/approve",
          AGREEMENTS + "/1/revoke",
          "/api/ops/price-schedules/1/notices"
        }) {
      assertError(post(path, "{}", null), ErrorCode.UNAUTHENTICATED);
      assertError(post(path, "{}", support), ErrorCode.FORBIDDEN);
    }
    mockMvc.perform(get(AGREEMENTS).cookie(billingSession)).andExpect(status().isOk());
    mockMvc.perform(get(AGREEMENTS).cookie(opsSession)).andExpect(status().isOk());
    assertError(mockMvc.perform(get(NOTICES).cookie(opsSession)), ErrorCode.FORBIDDEN);
    assertError(post(AGREEMENTS + "/1/approve", "", opsSession), ErrorCode.FORBIDDEN);
    assertError(post(AGREEMENTS, "{}", billingSession), ErrorCode.FORBIDDEN);
    TestData.Staff owner = staff(OfficeMembershipRole.OWNER);
    assertError(
        mockMvc.perform(get(AGREEMENTS).cookie(memberSession(owner))), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 합의_등록은_이벤트와_가격_대상과_가입_창을_검사하고_보장_기간을_계산한다() throws Exception {
    TestData.Staff owner = staff(OfficeMembershipRole.OWNER);
    long office = owner.office().getId();
    long event = startEvent();
    long price = startPrice("2027-07-START");
    long general = fixtures.trialEvent("GENERAL", "DURATION", days(-1), days(30), null, "ACTIVE");
    LocalDate today = TimeRules.displayDate(now);

    assertFieldError(
        post(AGREEMENTS, createBody(office, general, price, today), opsSession),
        "trialEventId",
        "NOT_ALLOWED");
    assertFieldError(
        post(
            AGREEMENTS,
            createBody(office, event, fixtures.standardPrice("STD-1", days(-30)), today),
            opsSession),
        "priceScheduleId",
        "NOT_ALLOWED");
    assertFieldError(
        post(
            AGREEMENTS,
            createBody(office, event, price, today)
                .replace("\"weeklyUseConfirmed\":true", "\"weeklyUseConfirmed\":false"),
            opsSession),
        "weeklyUseConfirmed",
        "INVALID_VALUE");
    assertError(
        post(AGREEMENTS, createBody(999_999, event, price, today), opsSession),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        post(AGREEMENTS, createBody(office, event, price, today.minusDays(5)), opsSession),
        ErrorCode.START_CUSTOMER_WINDOW_CLOSED);

    post(AGREEMENTS, createBody(office, event, price, today), opsSession)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("REGISTERED"))
        .andExpect(jsonPath("$.priceVersion").value("2027-07-START"))
        .andExpect(jsonPath("$.version").value(1));
    assertError(
        post(AGREEMENTS, createBody(office, event, price, today), opsSession),
        ErrorCode.START_CUSTOMER_AGREEMENT_EXISTS);
    Timestamp starts =
        jdbcTemplate.queryForObject(
            "SELECT price_starts_at FROM billing_start_customer_agreement", Timestamp.class);
    Timestamp ends =
        jdbcTemplate.queryForObject(
            "SELECT price_ends_at FROM billing_start_customer_agreement", Timestamp.class);
    assertThat(TimeRules.toDisplay(ends.toInstant()))
        .isEqualTo(TimeRules.toDisplay(starts.toInstant()).plusMonths(12));
    mockMvc
        .perform(get(AGREEMENTS).param("status", "REGISTERED").cookie(billingSession))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].officeName").value(owner.office().getName()))
        .andExpect(jsonPath("$.items[0].createdByOperatorId").value(ops.id()))
        .andExpect(jsonPath("$.cohorts[0].trialEventId").value(event))
        .andExpect(jsonPath("$.cohorts[0].approvedCount").value(0))
        .andExpect(jsonPath("$.cohorts[0].maxOffices").value(15))
        .andExpect(jsonPath("$.cohorts[0].targetCount").value(10));
    assertThat(auditCount("START_CUSTOMER_AGREEMENT_REGISTERED")).isEqualTo(1);
  }

  @Test
  void 승인은_자기_승인을_막고_DB_역할을_다시_보며_구독을_승격한다() throws Exception {
    TestData.Staff owner = staff(OfficeMembershipRole.OWNER);
    long event = startEvent();
    long price = startPrice("2027-07-START");
    long general = fixtures.trialEvent("GENERAL", "DURATION", days(-20), days(-10), null, "ENDED");
    long subscriptionId =
        fixtures
            .subscription(owner.office().getId())
            .status("TRIAL")
            .trial(general, days(-10), days(10))
            .insert();
    Operator both = operator("한겸직", OperatorRole.OPS, OperatorRole.BILLING);
    Cookie bothSession = sessionOf(both);
    String body =
        post(
                AGREEMENTS,
                createBody(owner.office().getId(), event, price, TimeRules.displayDate(now)),
                bothSession)
            .andReturn()
            .getResponse()
            .getContentAsString();
    long agreement = Long.parseLong(body.replaceAll("(?s).*\"agreementId\":(\\d+).*", "$1"));
    String approve = AGREEMENTS + "/" + agreement + "/approve";

    assertFieldError(postIfMatch(approve, "", billingSession, null), "If-Match", "REQUIRED");
    assertError(postIfMatch(approve, "", bothSession, "1"), ErrorCode.SELF_APPROVAL_FORBIDDEN);
    Operator demoted = operator("강회수", OperatorRole.BILLING);
    Cookie demotedSession = sessionOf(demoted);
    jdbcTemplate.update(
        "DELETE FROM operator_role_assignment WHERE operator_account_id = ?", demoted.id());
    assertError(postIfMatch(approve, "", demotedSession, "1"), ErrorCode.FORBIDDEN);
    assertError(postIfMatch(approve, "", billingSession, "2"), ErrorCode.VERSION_CONFLICT);

    postIfMatch(approve, "", billingSession, "1")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.approvedByOperatorId").value(billing.id()))
        .andExpect(jsonPath("$.cohort.approvedCount").value(1))
        .andExpect(jsonPath("$.cohort.maxOffices").value(15))
        .andExpect(jsonPath("$.subscription.subscriptionId").value(subscriptionId))
        .andExpect(jsonPath("$.subscription.status").value("TRIAL"))
        .andExpect(jsonPath("$.subscription.promoted").value(true))
        .andExpect(jsonPath("$.version").value(2));
    Timestamp trialEnds =
        jdbcTemplate.queryForObject(
            "SELECT trial_ends_at FROM subscription WHERE id = ?", Timestamp.class, subscriptionId);
    assertThat(trialEnds.toInstant()).isBetween(days(60).minusSeconds(1), days(60).plusSeconds(1));
    assertError(postIfMatch(approve, "", billingSession, "2"), ErrorCode.INVALID_TRANSITION);
    assertThat(auditCount("START_CUSTOMER_AGREEMENT_APPROVED")).isEqualTo(1);
  }

  @Test
  void 승인은_상한과_가입_창을_이벤트_단위로_검사한다() throws Exception {
    long event = startEvent();
    long price = startPrice("2027-07-START");
    jdbcTemplate.update("UPDATE trial_event SET max_offices = 1 WHERE id = ?", event);
    long first = createAgreement(staff(OfficeMembershipRole.OWNER).office().getId(), event, price);
    long second = createAgreement(staff(OfficeMembershipRole.OWNER).office().getId(), event, price);
    postIfMatch(AGREEMENTS + "/" + first + "/approve", "", billingSession, "1")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subscription").value(Matchers.nullValue()));
    assertError(
        postIfMatch(AGREEMENTS + "/" + second + "/approve", "", billingSession, "1"),
        ErrorCode.START_CUSTOMER_QUOTA_EXCEEDED);
    jdbcTemplate.update(
        "UPDATE trial_event SET max_offices = 5, ends_at = now() - interval '1 minute',"
            + " starts_at = now() - interval '2 days' WHERE id = ?",
        event);
    assertError(
        postIfMatch(AGREEMENTS + "/" + second + "/approve", "", billingSession, "1"),
        ErrorCode.START_CUSTOMER_WINDOW_CLOSED);
    assertError(
        postIfMatch(AGREEMENTS + "/999999/approve", "", billingSession, "1"),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 취소는_사유와_버전을_검사하고_청구서가_있으면_인상_안내를_만들며_적용_전까지_합의_가격을_쓴다() throws Exception {
    TestData.Staff owner = staff(OfficeMembershipRole.OWNER);
    long office = owner.office().getId();
    fixtures.standardPrice("2026-12-STD", days(-100));
    long event = startEvent();
    long startPriceId = fixtures.price("2027-START", "START_CUSTOMER", days(-30), "ACTIVE", 29_900);
    long subscriptionId = activeSubscription(owner);
    long consent =
        fixtures.consent(subscriptionId, owner.user().getId(), office, 29_900, 2_718, 29_900);
    fixtures.invoice(office, subscriptionId, consent).amount(29_900).insert();
    long agreement =
        fixtures.startCustomerAgreement(
            office, event, startPriceId, days(-30), days(300), "APPROVED");
    String revoke = AGREEMENTS + "/" + agreement + "/revoke";

    assertFieldError(postIfMatch(revoke, "{}", billingSession, "1"), "reason", "REQUIRED");
    assertError(
        postIfMatch(revoke, "{\"reason\":null}", billingSession, "1"), ErrorCode.INVALID_REQUEST);
    assertError(
        postIfMatch(revoke, "{\"reason\":\"   \"}", billingSession, "1"),
        ErrorCode.INVALID_REQUEST);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM billing_start_customer_agreement WHERE id = ?",
                String.class,
                agreement))
        .isEqualTo("APPROVED");
    assertError(
        postIfMatch(revoke, "{\"reason\":\"철회\"}", billingSession, "9"),
        ErrorCode.VERSION_CONFLICT);
    postIfMatch(revoke, "{\"reason\":\"합의서 체결 철회\"}", billingSession, "1")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REVOKED"))
        .andExpect(jsonPath("$.freeEndsAtKept").value(true))
        .andExpect(jsonPath("$.priceChangeNoticeId").isNumber())
        .andExpect(jsonPath("$.version").value(2));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT change_kind FROM pricing_change_notice WHERE subscription_id = ?",
                String.class,
                subscriptionId))
        .isEqualTo("INCREASE");
    assertThat(selector.find(office, days(1)).orElseThrow().startCustomer()).isTrue();
    assertThat(selector.find(office, days(70)).orElseThrow().startCustomer()).isFalse();
    assertThat(planChanges(owner)).isEqualTo(1);
    assertError(
        postIfMatch(revoke, "{\"reason\":\"다시\"}", billingSession, "2"),
        ErrorCode.INVALID_TRANSITION);
    assertThat(auditCount("START_CUSTOMER_AGREEMENT_REVOKED")).isEqualTo(1);
  }

  @Test
  void 가격_변경_안내는_대상을_고르고_건너뛴_사유를_돌려주며_다시_보내도_한_건이다() throws Exception {
    fixtures.standardPrice("2026-12-STD", days(-100));
    long next = fixtures.price("2027-09-STD", "STANDARD", days(40), "SCHEDULED", 45_000);
    TestData.Staff paying = staff(OfficeMembershipRole.OWNER);
    long payingSubscription = activeSubscription(paying);
    TestData.Staff trial = staff(OfficeMembershipRole.OWNER);
    fixtures
        .subscription(trial.office().getId())
        .status("TRIAL")
        .trial(
            fixtures.trialEvent("GENERAL", "DURATION", days(-20), days(-10), null, "ENDED"),
            days(-10),
            days(10))
        .insert();
    TestData.Staff locked = staff(OfficeMembershipRole.OWNER);
    activeSubscription(locked);
    long event = startEvent();
    long startPriceId = fixtures.price("2027-START", "START_CUSTOMER", days(-30), "ACTIVE", 29_900);
    fixtures.startCustomerAgreement(
        locked.office().getId(), event, startPriceId, days(-30), days(300), "APPROVED");
    String path = "/api/ops/price-schedules/" + next + "/notices";

    post(path, "{}", billingSession)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.priceScheduleId").value(next))
        .andExpect(jsonPath("$.createdCount").value(1))
        .andExpect(jsonPath("$.skippedCount").value(2))
        .andExpect(
            jsonPath("$.skipped[?(@.officeId == " + trial.office().getId() + ")].reason")
                .value("NO_PAYING_SUBSCRIPTION"))
        .andExpect(
            jsonPath("$.skipped[?(@.officeId == " + locked.office().getId() + ")].reason")
                .value("START_CUSTOMER_PRICE_LOCK"));
    post(path, "{\"officeIds\":[" + paying.office().getId() + "]}", billingSession)
        .andExpect(jsonPath("$.createdCount").value(0))
        .andExpect(jsonPath("$.skipped[0].reason").value("ALREADY_NOTIFIED"));
    assertThat(planChanges(paying)).isEqualTo(1);
    Timestamp applies =
        jdbcTemplate.queryForObject(
            "SELECT applies_from_period_start FROM pricing_change_notice WHERE subscription_id = ?",
            Timestamp.class,
            payingSubscription);
    assertThat(applies.toInstant()).isAfterOrEqualTo(days(40).minusSeconds(1));
    assertThat(
            count("SELECT count(*) FROM pricing_change_notice WHERE notification_id IS NOT NULL"))
        .isEqualTo(1);

    mockMvc
        .perform(get(NOTICES).param("decision", "PENDING").cookie(billingSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].changeKind").value("INCREASE"))
        .andExpect(jsonPath("$.items[0].fromPriceVersion").value("2026-12-STD"))
        .andExpect(jsonPath("$.items[0].toPriceVersion").value("2027-09-STD"))
        .andExpect(jsonPath("$.items[0].officeName").value(paying.office().getName()))
        .andExpect(jsonPath("$.summary.pending").value(1))
        .andExpect(jsonPath("$.summary.agreed").value(0));
    assertFieldError(
        mockMvc.perform(get(NOTICES).param("decision", "NOPE").cookie(billingSession)),
        "decision",
        "NOT_ALLOWED");
    assertThat(auditCount("PRICE_CHANGE_NOTICES_CREATED")).isEqualTo(2);
  }

  @Test
  void 가격_변경_안내_생성은_예약된_표준_버전과_30일_규칙을_검사한다() throws Exception {
    long soon = fixtures.price("2027-01-STD", "STANDARD", days(10), "SCHEDULED", 45_000);
    long active = fixtures.standardPrice("2026-12-STD", days(-100));
    long start = fixtures.price("2027-START", "START_CUSTOMER", days(40), "SCHEDULED", 29_900);
    assertError(
        post("/api/ops/price-schedules/" + soon + "/notices", "{}", billingSession),
        ErrorCode.PRICE_NOTICE_PERIOD_TOO_SHORT);
    assertError(
        post("/api/ops/price-schedules/" + active + "/notices", "{}", billingSession),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        post("/api/ops/price-schedules/" + start + "/notices", "{}", billingSession),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        post("/api/ops/price-schedules/999999/notices", "{}", billingSession),
        ErrorCode.RESOURCE_NOT_FOUND);
    String tooMany =
        LongStream.rangeClosed(1, 501).mapToObj(String::valueOf).collect(Collectors.joining(","));
    assertFieldError(
        post(
            "/api/ops/price-schedules/" + soon + "/notices",
            "{\"officeIds\":[" + tooMany + "]}",
            billingSession),
        "officeIds",
        "TOO_LONG");
  }

  @Test
  void 요금_안내_배치는_재안내와_보장_종료와_결제_사전_안내를_한_번씩_남긴다() {
    fixtures.standardPrice("2026-12-STD", days(-100));
    TestData.Staff remindOwner = staff(OfficeMembershipRole.OWNER);
    long remindSubscription = activeSubscription(remindOwner);
    long fromPrice = fixtures.price("OLD-STD", "STANDARD", days(-300), "ENDED", 30_000);
    long toPrice = fixtures.price("NEW-STD", "STANDARD", days(6), "SCHEDULED", 45_000);
    jdbcTemplate.update(
        "INSERT INTO pricing_change_notice (office_id, subscription_id, from_price_schedule_id,"
            + " to_price_schedule_id, change_kind, member_count_snapshot, current_amount_incl_vat,"
            + " new_amount_incl_vat, notified_at, effective_at, applies_from_period_start,"
            + " response_due_at) VALUES (?, ?, ?, ?, 'INCREASE', 1, 30000, 45000, ?, ?, ?, ?)",
        remindOwner.office().getId(),
        remindSubscription,
        fromPrice,
        toPrice,
        Timestamp.from(days(-30)),
        Timestamp.from(days(6)),
        Timestamp.from(days(25)),
        Timestamp.from(days(6)));

    TestData.Staff guaranteeOwner = staff(OfficeMembershipRole.OWNER);
    long guaranteeSubscription =
        fixtures
            .subscription(guaranteeOwner.office().getId())
            .status("ACTIVE")
            .period(days(-20), days(10))
            .insert();
    long event = startEvent();
    long startPriceId =
        fixtures.price("2027-START", "START_CUSTOMER", days(-300), "ACTIVE", 29_900);
    fixtures.startCustomerAgreement(
        guaranteeOwner.office().getId(), event, startPriceId, days(-300), days(40), "APPROVED");

    planNoticeJobs.runAll(now);
    planNoticeJobs.runAll(now);

    assertThat(planChanges(remindOwner)).isEqualTo(1);
    assertThat(planChanges(guaranteeOwner)).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT change_kind FROM pricing_change_notice WHERE subscription_id = ?",
                String.class,
                guaranteeSubscription))
        .isEqualTo("INCREASE");
    assertThat(
            count(
                "SELECT count(*) FROM notification WHERE recipient_user_id = ? AND"
                    + " notification_type = 'PAYMENT' AND message LIKE '%결제됩니다'",
                guaranteeOwner.user().getId()))
        .isZero();
    List<String> payments =
        jdbcTemplate.queryForList(
            "SELECT message FROM notification WHERE recipient_user_id = ? AND notification_type"
                + " = 'PAYMENT'",
            String.class,
            remindOwner.user().getId());
    assertThat(payments).isEmpty();

    TestData.Staff renewOwner = staff(OfficeMembershipRole.OWNER);
    fixtures
        .subscription(renewOwner.office().getId())
        .status("ACTIVE")
        .period(days(-27), days(3))
        .insert();
    planNoticeJobs.runPaymentUpcomingNotices(now);
    planNoticeJobs.runPaymentUpcomingNotices(now);
    List<String> renewMessages =
        jdbcTemplate.queryForList(
            "SELECT message FROM notification WHERE recipient_user_id = ? AND notification_type"
                + " = 'PAYMENT'",
            String.class,
            renewOwner.user().getId());
    assertThat(renewMessages).hasSize(1);
    assertThat(renewMessages.getFirst()).contains("39,900원이 결제됩니다");
  }
}
