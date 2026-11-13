package com.sairo.be.domain.ops.controller;

import static com.sairo.be.support.AuthFlowSupport.withCsrf;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.billing.service.PriceScheduleActivationService;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsConsoleTestBase;
import com.sairo.be.support.fixture.BillingFixtures;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class OpsTrialPriceIntegrationTest extends OpsConsoleTestBase {

  private static final String PRICES = "/api/ops/price-schedules";
  private static final String EVENTS = "/api/ops/trial-events";
  private static final String CALENDAR = "/api/ops/business-calendar";

  @Autowired private PriceScheduleActivationService activation;

  private BillingFixtures fixtures;
  private Operator billing;
  private Cookie billingSession;
  private Operator billingApprover;
  private Cookie billingApproverSession;
  private Operator ops;
  private Cookie opsSession;
  private Operator opsApprover;
  private Cookie opsApproverSession;
  private Instant now;

  @BeforeEach
  void setUp() {
    fixtures = new BillingFixtures(jdbcTemplate);
    billing = operator("박결제", OperatorRole.BILLING);
    billingSession = sessionOf(billing);
    stepUp(billingSession);
    billingApprover = operator("이결제", OperatorRole.BILLING);
    billingApproverSession = sessionOf(billingApprover);
    stepUp(billingApproverSession);
    ops = operator("최운영", OperatorRole.OPS);
    opsSession = sessionOf(ops);
    opsApprover = operator("한운영", OperatorRole.OPS);
    opsApproverSession = sessionOf(opsApprover);
    now = Instant.now();
  }

  private static String priceBody(String effectiveAt, String noticeAt, String audience) {
    return "{\"soloAmount\":39900,\"teamFirstExtra\":10000,\"teamAfterFourExtra\":8000,"
        + "\"effectiveAt\":\""
        + effectiveAt
        + "\",\"noticeAt\":\""
        + noticeAt
        + "\""
        + (audience == null ? "" : ",\"audience\":\"" + audience + "\"")
        + "}";
  }

  private long createPrice(String effectiveAt, String noticeAt, String audience) throws Exception {
    String body =
        post(PRICES, priceBody(effectiveAt, noticeAt, audience), billingSession)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return Long.parseLong(body.replaceAll("(?s).*\"scheduleId\":(\\d+).*", "$1"));
  }

  private String eventBody(Instant startsAt, Instant endsAt, String extra) {
    return "{\"name\":\"가을 체험\",\"startsAt\":\""
        + OffsetDateTime.ofInstant(startsAt, java.time.ZoneOffset.ofHours(9))
        + "\",\"endsAt\":\""
        + OffsetDateTime.ofInstant(endsAt, java.time.ZoneOffset.ofHours(9))
        + "\""
        + extra
        + "}";
  }

  private long createEvent(Instant startsAt, Instant endsAt, String extra) throws Exception {
    String body =
        post(EVENTS, eventBody(startsAt, endsAt, extra), opsSession)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return Long.parseLong(body.replaceAll("(?s).*\"eventId\":(\\d+).*", "$1"));
  }

  private Instant days(long value) {
    return now.plus(Duration.ofDays(value));
  }

  private ResultActions putCalendar(String date, String body, Cookie cookie) throws Exception {
    var builder = put(CALENDAR + "/" + date).contentType(MediaType.APPLICATION_JSON).content(body);
    if (cookie != null) {
      builder.cookie(cookie);
    }
    return mockMvc.perform(withCsrf(builder));
  }

  private java.sql.Timestamp trialEnd(TestData.Staff owner) {
    return jdbcTemplate.queryForObject(
        "SELECT trial_ends_at FROM subscription WHERE office_id = ?",
        java.sql.Timestamp.class,
        owner.office().getId());
  }

  private String rowStatus(String table, long id) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM " + table + " WHERE id = ?", String.class, id);
  }

  @Test
  void 새_엔드포인트는_세션이_없으면_401이고_역할이_맞지_않으면_403이다() throws Exception {
    Cookie support = sessionOf(operator("김지원", OperatorRole.SUPPORT));
    for (String path : new String[] {PRICES, EVENTS, EVENTS + "/1/preview", CALENDAR}) {
      assertError(mockMvc.perform(get(path)), ErrorCode.UNAUTHENTICATED);
      assertError(mockMvc.perform(get(path).cookie(support)), ErrorCode.FORBIDDEN);
    }
    for (String path :
        new String[] {
          PRICES, PRICES + "/1/approve", EVENTS, EVENTS + "/1/approve", EVENTS + "/1/stop"
        }) {
      assertError(post(path, "{}", null), ErrorCode.UNAUTHENTICATED);
      assertError(post(path, "{}", support), ErrorCode.FORBIDDEN);
    }
    String calendarBody = "{\"isBusinessDay\":false}";
    assertError(putCalendar("2030-01-01", calendarBody, null), ErrorCode.UNAUTHENTICATED);
    assertError(putCalendar("2030-01-01", calendarBody, support), ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(get(PRICES).cookie(opsSession)), ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(get(EVENTS).cookie(billingSession)), ErrorCode.FORBIDDEN);
    assertError(putCalendar("2030-01-01", calendarBody, billingSession), ErrorCode.FORBIDDEN);
    TestData.Staff owner = staff(OfficeMembershipRole.OWNER);
    Cookie member = memberSession(owner);
    for (String path : new String[] {PRICES, EVENTS, CALENDAR}) {
      assertError(mockMvc.perform(get(path).cookie(member)), ErrorCode.UNAUTHENTICATED);
    }
    assertError(
        post(PRICES + "/999999/approve", "", billingApproverSession), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        post(EVENTS + "/999999/approve", "", opsApproverSession), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(post(EVENTS + "/999999/stop", "", opsSession), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(get(EVENTS + "/999999/preview").cookie(opsSession)),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 요금표_등록은_공지_30일_규칙을_검사하고_버전을_서버가_정한다() throws Exception {
    assertFieldError(
        post(
            PRICES,
            priceBody("2030-03-01T00:00:00+09:00", "2030-02-10T00:00:00+09:00", null),
            billingSession),
        "noticeAt",
        "OUT_OF_RANGE");
    assertFieldError(
        post(
            PRICES,
            priceBody("2030-03-01T00:00:00+09:00", "2030-01-01T00:00:00+09:00", "VIP"),
            billingSession),
        "audience",
        "NOT_ALLOWED");
    assertFieldError(
        post(
            PRICES,
            priceBody("2030-03-01T00:00:00+09:00", "2030-01-01T00:00:00+09:00", null)
                .replace("39900", "-1"),
            billingSession),
        "soloAmount",
        "OUT_OF_RANGE");
    assertError(
        post(PRICES, "{\"soloAmount\":1,\"unknown\":1}", billingSession),
        ErrorCode.INVALID_REQUEST);

    post(
            PRICES,
            priceBody("2030-03-01T00:00:00+09:00", "2030-01-01T00:00:00+09:00", null),
            billingSession)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.version").value("2030-03-STD"))
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.audience").value("STANDARD"));
    post(
            PRICES,
            priceBody("2030-03-15T00:00:00+09:00", "2030-01-01T00:00:00+09:00", null),
            billingSession)
        .andExpect(jsonPath("$.version").value("2030-03-STD-2"));
    post(
            PRICES,
            priceBody("2030-03-20T00:00:00+09:00", "2030-01-01T00:00:00+09:00", null),
            billingSession)
        .andExpect(jsonPath("$.version").value("2030-03-STD-3"));
    post(
            PRICES,
            priceBody("2030-02-28T15:00:00Z", "2030-01-01T00:00:00+09:00", "START_CUSTOMER"),
            billingSession)
        .andExpect(jsonPath("$.version").value("2030-03-START"))
        .andExpect(jsonPath("$.audience").value("START_CUSTOMER"));

    mockMvc
        .perform(get(PRICES).cookie(billingSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(4))
        .andExpect(jsonPath("$.items[0].version").value("2030-03-STD-3"))
        .andExpect(jsonPath("$.items[0].createdByOperatorId").value(billing.id()));
    mockMvc
        .perform(get(PRICES).param("audience", "START_CUSTOMER").cookie(billingSession))
        .andExpect(jsonPath("$.totalElements").value(1));
    assertFieldError(
        mockMvc.perform(get(PRICES).param("status", "LIVE").cookie(billingSession)),
        "status",
        "NOT_ALLOWED");
    assertThat(auditCount("PRICE_SCHEDULE_CREATED")).isEqualTo(4);
  }

  @Test
  void 요금표_승인은_작성자와_다른_결제담당만_하고_효력_시각으로_예약과_시행을_나눈다() throws Exception {
    long future = createPrice("2030-05-01T00:00:00+09:00", "2030-03-01T00:00:00+09:00", null);
    long past = createPrice("2020-05-01T00:00:00+09:00", "2020-03-01T00:00:00+09:00", null);

    assertError(
        post(PRICES + "/" + future + "/approve", "", billingSession),
        ErrorCode.SELF_APPROVAL_FORBIDDEN);
    post(PRICES + "/" + future + "/approve", "", billingApproverSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SCHEDULED"))
        .andExpect(jsonPath("$.version").value("2030-05-STD"))
        .andExpect(jsonPath("$.approvedByOperatorId").value(billingApprover.id()));
    assertError(
        post(PRICES + "/" + future + "/approve", "", billingApproverSession),
        ErrorCode.INVALID_TRANSITION);
    post(PRICES + "/" + past + "/approve", "", billingApproverSession)
        .andExpect(jsonPath("$.status").value("ACTIVE"));

    assertFieldError(
        post(
            PRICES,
            priceBody("2030-05-01T00:00:00+09:00", "2030-03-01T00:00:00+09:00", null),
            billingSession),
        "effectiveAt",
        "NOT_ALLOWED");
    long sameTimeStart =
        createPrice("2030-05-01T00:00:00+09:00", "2030-03-01T00:00:00+09:00", "START_CUSTOMER");
    post(PRICES + "/" + sameTimeStart + "/approve", "", billingApproverSession)
        .andExpect(jsonPath("$.status").value("SCHEDULED"));

    long draft = createPrice("2030-07-01T00:00:00+09:00", "2030-05-01T00:00:00+09:00", null);
    fixtures.price(
        "2030-07-OTHER", "STANDARD", Instant.parse("2030-06-30T15:00:00Z"), "SCHEDULED", 45_000);
    assertError(
        post(PRICES + "/" + draft + "/approve", "", billingApproverSession),
        ErrorCode.INVALID_TRANSITION);

    long revoked = createPrice("2030-09-01T00:00:00+09:00", "2030-07-01T00:00:00+09:00", null);
    jdbcTemplate.update(
        "DELETE FROM operator_role_assignment WHERE operator_account_id = ?", billingApprover.id());
    assertError(
        post(PRICES + "/" + revoked + "/approve", "", billingApproverSession), ErrorCode.FORBIDDEN);
    assertThat(rowStatus("price_schedule", revoked)).isEqualTo("DRAFT");
    assertThat(auditCount("PRICE_SCHEDULE_APPROVED")).isEqualTo(3);
  }

  @Test
  void 시행_전환_배치는_예약된_요금표와_이벤트를_효력_시각에_시행으로_바꾼다() throws Exception {
    long price = createPrice("2030-05-01T00:00:00+09:00", "2030-03-01T00:00:00+09:00", null);
    post(PRICES + "/" + price + "/approve", "", billingApproverSession)
        .andExpect(jsonPath("$.status").value("SCHEDULED"));
    long event = createEvent(days(10), days(40), ",\"officeCreatedDuring\":false");
    post(EVENTS + "/" + event + "/approve", "", opsApproverSession)
        .andExpect(jsonPath("$.status").value("SCHEDULED"));

    var before = activation.activateDue(now);
    assertThat(before.priceSchedules()).isZero();
    assertThat(before.trialEvents()).isZero();
    assertThat(rowStatus("price_schedule", price)).isEqualTo("SCHEDULED");

    var eventStart = activation.activateDue(days(10).plusSeconds(1));
    assertThat(eventStart.trialEvents()).isEqualTo(1);
    assertThat(rowStatus("trial_event", event)).isEqualTo("ACTIVE");
    assertThat(rowStatus("price_schedule", price)).isEqualTo("SCHEDULED");

    var priceStart = activation.activateDue(Instant.parse("2030-04-30T15:00:00Z"));
    assertThat(priceStart.priceSchedules()).isEqualTo(1);
    assertThat(rowStatus("price_schedule", price)).isEqualTo("ACTIVE");
    assertThat(activation.activateDue(Instant.parse("2030-05-02T00:00:00Z")).priceSchedules())
        .isZero();
  }

  @Test
  void 체험_이벤트_등록은_기간과_무료_종료_방식과_대상을_검사한다() throws Exception {
    assertFieldError(
        post(EVENTS, eventBody(days(1), days(1), ""), opsSession), "endsAt", "OUT_OF_RANGE");
    assertFieldError(
        post(EVENTS, eventBody(days(1), days(10), ",\"freeEndMode\":\"FIXED_END\""), opsSession),
        "fixedFreeEndsAt",
        "REQUIRED");
    assertFieldError(
        post(
            EVENTS,
            eventBody(
                days(1),
                days(10),
                ",\"fixedFreeEndsAt\":\""
                    + OffsetDateTime.ofInstant(days(30), java.time.ZoneOffset.UTC)
                    + "\""),
            opsSession),
        "fixedFreeEndsAt",
        "NOT_ALLOWED");
    assertFieldError(
        post(EVENTS, eventBody(days(1), days(10), ",\"maxOffices\":10"), opsSession),
        "maxOffices",
        "NOT_ALLOWED");
    assertFieldError(
        post(EVENTS, eventBody(days(1), days(10), ",\"freeEndMode\":\"FOREVER\""), opsSession),
        "freeEndMode",
        "NOT_ALLOWED");
    assertFieldError(
        post(EVENTS, eventBody(days(1), days(10), "").replace("가을 체험", " "), opsSession),
        "name",
        "REQUIRED");

    post(EVENTS, eventBody(days(1), days(10), ""), opsSession)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("REVIEW"))
        .andExpect(jsonPath("$.freeEndMode").value("DURATION"))
        .andExpect(jsonPath("$.fixedFreeEndsAt").value(Matchers.nullValue()))
        .andExpect(jsonPath("$.audience").value("GENERAL"))
        .andExpect(jsonPath("$.maxOffices").value(Matchers.nullValue()));
    post(
            EVENTS,
            eventBody(
                days(1),
                days(10),
                ",\"freeEndMode\":\"FIXED_END\",\"fixedFreeEndsAt\":\""
                    + OffsetDateTime.ofInstant(days(60), java.time.ZoneOffset.UTC)
                    + "\",\"audience\":\"START_CUSTOMER\",\"maxOffices\":15"),
            opsSession)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.freeEndMode").value("FIXED_END"))
        .andExpect(jsonPath("$.audience").value("START_CUSTOMER"))
        .andExpect(jsonPath("$.maxOffices").value(15));

    mockMvc
        .perform(get(EVENTS).cookie(opsSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.items[0].officeCreatedDuring").value(true))
        .andExpect(jsonPath("$.items[0].firstApplicationDuring").value(true));
    mockMvc
        .perform(get(EVENTS).param("audience", "START_CUSTOMER").cookie(opsSession))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].createdByOperatorId").value(ops.id()));
    assertThat(auditCount("TRIAL_EVENT_CREATED")).isEqualTo(2);
  }

  @Test
  void 체험_이벤트_승인은_작성자와_다른_운영담당만_하고_가입_창_겹침을_막는다() throws Exception {
    long started = createEvent(days(-1), days(20), "");
    assertError(
        post(EVENTS + "/" + started + "/approve", "", opsSession),
        ErrorCode.SELF_APPROVAL_FORBIDDEN);
    post(EVENTS + "/" + started + "/approve", "", opsApproverSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.approvedByOperatorId").value(opsApprover.id()));
    assertError(
        post(EVENTS + "/" + started + "/approve", "", opsApproverSession),
        ErrorCode.INVALID_TRANSITION);

    long overlapping = createEvent(days(10), days(30), "");
    assertError(
        post(EVENTS + "/" + overlapping + "/approve", "", opsApproverSession),
        ErrorCode.TRIAL_EVENT_WINDOW_OVERLAP);
    assertThat(rowStatus("trial_event", overlapping)).isEqualTo("REVIEW");

    long adjacent = createEvent(days(20), days(50), "");
    post(EVENTS + "/" + adjacent + "/approve", "", opsApproverSession)
        .andExpect(jsonPath("$.status").value("SCHEDULED"));
    long startCustomer =
        createEvent(
            days(10),
            days(30),
            ",\"freeEndMode\":\"FIXED_END\",\"fixedFreeEndsAt\":\""
                + OffsetDateTime.ofInstant(days(90), java.time.ZoneOffset.UTC)
                + "\",\"audience\":\"START_CUSTOMER\",\"maxOffices\":15");
    post(EVENTS + "/" + startCustomer + "/approve", "", opsApproverSession)
        .andExpect(jsonPath("$.status").value("SCHEDULED"));

    long revoked = createEvent(days(100), days(120), "");
    jdbcTemplate.update(
        "DELETE FROM operator_role_assignment WHERE operator_account_id = ?", opsApprover.id());
    assertError(
        post(EVENTS + "/" + revoked + "/approve", "", opsApproverSession), ErrorCode.FORBIDDEN);
    assertThat(auditCount("TRIAL_EVENT_APPROVED")).isEqualTo(3);
  }

  @Test
  void 체험_이벤트_중단은_종료_시각을_줄이고_부여한_체험을_바꾸지_않는다() throws Exception {
    long event = createEvent(days(-1), days(20), ",\"firstApplicationDuring\":false");
    assertError(post(EVENTS + "/" + event + "/stop", "", opsSession), ErrorCode.INVALID_TRANSITION);
    post(EVENTS + "/" + event + "/approve", "", opsApproverSession)
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    TestData.Staff owner = staff(OfficeMembershipRole.OWNER);
    Instant trialEndsAt = days(59);
    fixtures
        .subscription(owner.office().getId())
        .status("TRIAL")
        .trial(event, now, trialEndsAt)
        .insert();
    java.sql.Timestamp grantedEnd = trialEnd(owner);

    String body =
        post(EVENTS + "/" + event + "/stop", "", opsSession)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ENDED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    Instant endsAt =
        OffsetDateTime.parse(body.replaceAll("(?s).*\"endsAt\":\"([^\"]+)\".*", "$1")).toInstant();
    assertThat(endsAt).isBefore(days(1));
    assertThat(rowStatus("trial_event", event)).isEqualTo("ENDED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT trial_event_id FROM subscription WHERE office_id = ?",
                Long.class,
                owner.office().getId()))
        .isEqualTo(event);
    assertThat(trialEnd(owner)).isEqualTo(grantedEnd);
    assertError(post(EVENTS + "/" + event + "/stop", "", opsSession), ErrorCode.INVALID_TRANSITION);

    long scheduled = createEvent(days(30), days(60), "");
    post(EVENTS + "/" + scheduled + "/approve", "", opsApproverSession)
        .andExpect(jsonPath("$.status").value("SCHEDULED"));
    post(EVENTS + "/" + scheduled + "/stop", "", opsSession)
        .andExpect(jsonPath("$.status").value("ENDED"));
    assertThat(auditCount("TRIAL_EVENT_STOPPED")).isEqualTo(2);
  }

  @Test
  void 체험_이벤트_미리보기는_대상_사무소와_다른_이벤트_중복_수혜를_보인다() throws Exception {
    TestData.Staff fresh = staff(OfficeMembershipRole.OWNER);
    TestData.Staff benefited = staff(OfficeMembershipRole.OWNER);
    long other = fixtures.trialEvent("GENERAL", "DURATION", days(-200), days(-100), null, "ENDED");
    fixtures
        .subscription(benefited.office().getId())
        .status("TRIAL")
        .trial(other, days(-150), days(10))
        .insert();
    long event = createEvent(days(-1), days(20), ",\"firstApplicationDuring\":false");

    mockMvc
        .perform(get(EVENTS + "/" + event + "/preview").cookie(opsSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.eventId").value(event))
        .andExpect(jsonPath("$.targetOfficeCount").value(2))
        .andExpect(jsonPath("$.duplicateBenefitCount").value(1))
        .andExpect(jsonPath("$.duplicateOffices[0].officeId").value(benefited.office().getId()))
        .andExpect(
            jsonPath("$.duplicateOffices[0].officeName").value(benefited.office().getName()));

    long later = createEvent(days(10), days(20), ",\"firstApplicationDuring\":false");
    mockMvc
        .perform(get(EVENTS + "/" + later + "/preview").cookie(opsSession))
        .andExpect(jsonPath("$.targetOfficeCount").value(0))
        .andExpect(jsonPath("$.duplicateOffices").isEmpty());

    long startEvent =
        fixtures.trialEvent("START_CUSTOMER", "FIXED_END", days(-1), days(30), days(60), "ACTIVE");
    long startPrice =
        fixtures.price("2031-01-START", "START_CUSTOMER", days(60), "SCHEDULED", 29_900);
    fixtures.startCustomerAgreement(
        fresh.office().getId(), startEvent, startPrice, days(60), days(400), "REGISTERED");
    mockMvc
        .perform(get(EVENTS + "/" + startEvent + "/preview").cookie(opsSession))
        .andExpect(jsonPath("$.targetOfficeCount").value(1))
        .andExpect(jsonPath("$.duplicateBenefitCount").value(0));
  }

  @Test
  void 영업일_달력은_날짜별로_반영하고_기간으로_조회한다() throws Exception {
    putCalendar("2030-09-16", "{\"isBusinessDay\":false,\"reason\":\"추석 연휴\"}", opsSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.calendarDate").value("2030-09-16"))
        .andExpect(jsonPath("$.isBusinessDay").value(false))
        .andExpect(jsonPath("$.reason").value("추석 연휴"));
    putCalendar("2030-09-21", "{\"isBusinessDay\":true,\"reason\":\"임시 영업\"}", opsSession)
        .andExpect(status().isOk());
    putCalendar("2030-09-16", "{\"isBusinessDay\":true}", opsSession)
        .andExpect(jsonPath("$.isBusinessDay").value(true))
        .andExpect(jsonPath("$.reason").value(Matchers.nullValue()));
    putCalendar("2031-01-01", "{\"isBusinessDay\":false,\"reason\":\"신정\"}", opsSession)
        .andExpect(status().isOk());

    assertFieldError(
        putCalendar("2030-13-01", "{\"isBusinessDay\":true}", opsSession),
        "date",
        "INVALID_FORMAT");
    assertFieldError(putCalendar("2030-09-17", "{}", opsSession), "isBusinessDay", "REQUIRED");
    assertFieldError(
        putCalendar(
            "2030-09-17",
            "{\"isBusinessDay\":false,\"reason\":\"" + "가".repeat(201) + "\"}",
            opsSession),
        "reason",
        "TOO_LONG");

    mockMvc
        .perform(
            get(CALENDAR).param("from", "2030-09-01").param("to", "2030-09-30").cookie(opsSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.items[0].calendarDate").value("2030-09-16"))
        .andExpect(jsonPath("$.items[1].isBusinessDay").value(true));
    mockMvc
        .perform(get(CALENDAR).cookie(opsSession))
        .andExpect(jsonPath("$.totalElements").value(3));
    assertFieldError(
        mockMvc.perform(
            get(CALENDAR).param("from", "2030-10-01").param("to", "2030-09-01").cookie(opsSession)),
        "to",
        "OUT_OF_RANGE");
    assertFieldError(
        mockMvc.perform(get(CALENDAR).param("from", "어제").cookie(opsSession)),
        "from",
        "INVALID_FORMAT");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT updated_by_operator_id FROM ops_business_calendar WHERE calendar_date ="
                    + " DATE '2030-09-16'",
                Long.class))
        .isEqualTo(ops.id());
    assertThat(auditCount("BUSINESS_CALENDAR_UPDATED")).isEqualTo(4);
  }

  @Test
  void 요금표_승인은_재인증이_없거나_만료되면_401이고_재인증_뒤에는_승인된다() throws Exception {
    long price = createPrice("2031-05-01T00:00:00+09:00", "2031-03-01T00:00:00+09:00", null);
    Cookie fresh = sessionOf(operator("정결제", OperatorRole.BILLING));
    String approve = PRICES + "/" + price + "/approve";

    assertError(post(approve, "", fresh), ErrorCode.STEP_UP_REQUIRED);
    expireStepUp(fresh);
    assertError(post(approve, "", fresh), ErrorCode.STEP_UP_REQUIRED);
    assertThat(rowStatus("price_schedule", price)).isEqualTo("DRAFT");
    assertThat(auditCount("PRICE_SCHEDULE_APPROVED")).isZero();
    assertError(post(PRICES + "/999999/approve", "", fresh), ErrorCode.RESOURCE_NOT_FOUND);

    stepUp(fresh);
    post(approve, "", fresh)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SCHEDULED"));
  }
}
