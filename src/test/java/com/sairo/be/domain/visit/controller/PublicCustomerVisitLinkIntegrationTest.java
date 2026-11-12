package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitIntegrationTestBase;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class PublicCustomerVisitLinkIntegrationTest extends VisitIntegrationTestBase {

  private record Fixture(
      TestData.Staff owner, Cookie cookie, long coordinationId, String token, LocalDate start) {}

  private Fixture fixture() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "이수요");
    LocalDate start = today().plusDays(10);
    JsonNode created =
        createCoordination(
            cookie,
            customer,
            null,
            start,
            start.plusDays(10),
            newProperty(cookie),
            newProperty(cookie));
    long id = created.get("coordinationId").longValue();
    return new Fixture(owner, cookie, id, issueToken(cookie, id), start);
  }

  private static String view(String token) {
    return "/api/public/customer-visit-links/" + token;
  }

  private static String submitUrl(String token) {
    return view(token) + "/available-times";
  }

  private static String slot(LocalDate date, int fromHour, int toHour) {
    return "{\"startsAt\":\""
        + date
        + "T"
        + String.format("%02d", fromHour)
        + ":00:00+09:00\",\"endsAt\":\""
        + date
        + "T"
        + String.format("%02d", toHour)
        + ":00:00+09:00\"}";
  }

  private static String body(String version, String... slots) {
    return "{\"timeSlots\":["
        + String.join(",", slots)
        + "],\"acknowledgedNoticeVersion\":\""
        + version
        + "\"}";
  }

  @Test
  void 조회는_사무소_담당자_후보_요약과_안내를_주고_상세주소와_캐시를_막는다() throws Exception {
    Fixture f = fixture();
    jdbcTemplate.update(
        "UPDATE property SET address = '서울시 강남구 역삼동 123-4', address_detail = '101동 202호'");

    String json =
        mockMvc
            .perform(authedGet(view(f.token()), null))
            .andExpect(status().isOk())
            .andExpect(
                header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.role").value("CUSTOMER"))
            .andExpect(jsonPath("$.managerLegalRole").value("REPRESENTATIVE_AGENT"))
            .andExpect(jsonPath("$.officeName").value(f.owner().office().getName()))
            .andExpect(jsonPath("$.officeContactPhone").exists())
            .andExpect(jsonPath("$.linkExpiresAt").exists())
            .andExpect(jsonPath("$.candidateWindow.startDate").value(f.start().toString()))
            .andExpect(jsonPath("$.candidateProperties.length()").value(2))
            .andExpect(jsonPath("$.candidateProperties[0].district").value("서울시 강남구"))
            .andExpect(jsonPath("$.submittedTimeSlots.length()").value(0))
            .andExpect(jsonPath("$.responseVersion").value(0))
            .andExpect(jsonPath("$.coordinationStatus").value("TIME_COLLECTING"))
            .andExpect(jsonPath("$.privacyNotice.noticeCode").value("GUEST_LINK_NOTICE_CUSTOMER"))
            .andExpect(jsonPath("$.privacyNotice.noticeVersion").value(NOTICE_VERSION))
            .andExpect(jsonPath("$.privacyNotice.retentionDays").value(90))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json).doesNotContain("101동", "역삼동", "customerName", "이수요");
  }

  @Test
  void 담당자가_중개보조원이면_법적_역할이_보조원으로_나온다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var deputy = staffIn(owner.office(), OfficeMembershipRole.DEPUTY);
    addAgentToRoster(owner.office().getId());
    Cookie cookie = cookieOf(owner);
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "이수요");
    long id =
        createCoordination(
                cookie,
                customer,
                deputy.membership().getId(),
                today().plusDays(10),
                today().plusDays(12),
                newProperty(cookie))
            .get("coordinationId")
            .longValue();
    String token = issueToken(cookie, id);

    mockMvc
        .perform(authedGet(view(token), null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.managerLegalRole").value("ASSISTANT"));
  }

  @Test
  void 잘못된_토큰은_404이고_응답에_캐시_금지가_붙는다() throws Exception {
    mockMvc
        .perform(authedGet(view("not-a-real-token"), null))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PUBLIC_LINK_NOT_FOUND"))
        .andExpect(
            header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    assertError(
        mockMvc.perform(jsonPostAs(submitUrl("not-a-real-token"), body(NOTICE_VERSION), null)),
        ErrorCode.PUBLIC_LINK_NOT_FOUND);
  }

  @Test
  void 만료된_링크는_PUBLIC_LINK_EXPIRED다() throws Exception {
    Fixture f = fixture();
    jdbcTemplate.update(
        "UPDATE visit_customer_link SET expires_at = now() - interval '1 minute',"
            + " issued_at = now() - interval '2 days' WHERE coordination_id = ?",
        f.coordinationId());

    assertError(mockMvc.perform(authedGet(view(f.token()), null)), ErrorCode.PUBLIC_LINK_EXPIRED);
    assertError(
        mockMvc.perform(
            jsonPostAs(submitUrl(f.token()), body(NOTICE_VERSION, slot(f.start(), 10, 12)), null)),
        ErrorCode.PUBLIC_LINK_EXPIRED);
  }

  @Test
  void 제출하면_응답_버전이_오르고_시간_수집_중이_조율_가능으로_바뀌며_이력이_남는다() throws Exception {
    Fixture f = fixture();

    mockMvc
        .perform(
            jsonPostAs(
                submitUrl(f.token()),
                body(NOTICE_VERSION, slot(f.start(), 10, 12), slot(f.start().plusDays(1), 13, 15)),
                null))
        .andExpect(status().isOk())
        .andExpect(
            header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
        .andExpect(jsonPath("$.coordinationId").value(f.coordinationId()))
        .andExpect(jsonPath("$.responseVersion").value(1))
        .andExpect(jsonPath("$.submittedAt").exists());

    assertThat(coordinationStatus(f.coordinationId())).isEqualTo("COORDINATABLE");
    List<Map<String, Object>> history =
        jdbcTemplate.queryForList(
            "SELECT from_status, to_status, source, actor_membership_id FROM"
                + " visit_coordination_status_history WHERE coordination_id = ? ORDER BY id",
            f.coordinationId());
    assertThat(history).hasSize(2);
    assertThat(history.get(1).get("from_status")).isEqualTo("TIME_COLLECTING");
    assertThat(history.get(1).get("to_status")).isEqualTo("COORDINATABLE");
    assertThat(history.get(1).get("source")).isEqualTo("CUSTOMER");
    assertThat(history.get(1).get("actor_membership_id")).isNull();
    mockMvc
        .perform(authedGet(view(f.token()), null))
        .andExpect(jsonPath("$.responseVersion").value(1))
        .andExpect(jsonPath("$.submittedTimeSlots.length()").value(2))
        .andExpect(jsonPath("$.coordinationStatus").value("COORDINATABLE"));
  }

  @Test
  void 다시_제출하면_이전_버전을_보존하고_최신_버전만_현재값이다() throws Exception {
    Fixture f = fixture();
    mockMvc
        .perform(
            jsonPostAs(
                submitUrl(f.token()),
                body(NOTICE_VERSION, slot(f.start(), 10, 12), slot(f.start(), 14, 16)),
                null))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            jsonPostAs(
                submitUrl(f.token()),
                body(NOTICE_VERSION, slot(f.start().plusDays(2), 9, 11)),
                null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.responseVersion").value(2));

    Integer total =
        jdbcTemplate.queryForObject("SELECT count(*) FROM visit_customer_time_slot", Integer.class);
    assertThat(total).isEqualTo(3);
    mockMvc
        .perform(authedGet(view(f.token()), null))
        .andExpect(jsonPath("$.responseVersion").value(2))
        .andExpect(jsonPath("$.submittedTimeSlots.length()").value(1));
    assertThat(coordinationStatus(f.coordinationId())).isEqualTo("COORDINATABLE");
    long versionAfterSecond = coordinationVersion(f.coordinationId());
    assertThat(versionAfterSecond).isGreaterThan(1L);
  }

  @Test
  void 빈_배열_제출은_취소로_받고_조율_가능에서_시간_수집_중으로_돌린다() throws Exception {
    Fixture f = fixture();
    mockMvc
        .perform(
            jsonPostAs(submitUrl(f.token()), body(NOTICE_VERSION, slot(f.start(), 10, 12)), null))
        .andExpect(status().isOk());

    mockMvc
        .perform(jsonPostAs(submitUrl(f.token()), body(NOTICE_VERSION), null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.responseVersion").value(2));

    assertThat(coordinationStatus(f.coordinationId())).isEqualTo("TIME_COLLECTING");
    mockMvc
        .perform(authedGet(view(f.token()), null))
        .andExpect(jsonPath("$.submittedTimeSlots.length()").value(0))
        .andExpect(jsonPath("$.responseVersion").value(2));
    Integer transitions =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM visit_coordination_status_history WHERE coordination_id = ?",
            Integer.class,
            f.coordinationId());
    assertThat(transitions).isEqualTo(3);
  }

  @Test
  void 최종_확정_필요에서_고객이_수정하면_조율_가능으로_되돌아간다() throws Exception {
    Fixture f = fixture();
    setCoordinationStatus(f.coordinationId(), "FINAL_CONFIRMATION_REQUIRED");

    mockMvc
        .perform(
            jsonPostAs(submitUrl(f.token()), body(NOTICE_VERSION, slot(f.start(), 10, 12)), null))
        .andExpect(status().isOk());

    assertThat(coordinationStatus(f.coordinationId())).isEqualTo("COORDINATABLE");
  }

  @Test
  void 재조율_필요에서는_제출을_받되_상태는_바꾸지_않는다() throws Exception {
    Fixture f = fixture();
    jdbcTemplate.update(
        "UPDATE visit_coordination SET status = 'RESCHEDULE_REQUIRED',"
            + " reschedule_trigger_type = 'SCHEDULE_CHANGED', reschedule_raised_at = now()"
            + " WHERE id = ?",
        f.coordinationId());

    mockMvc
        .perform(
            jsonPostAs(submitUrl(f.token()), body(NOTICE_VERSION, slot(f.start(), 10, 12)), null))
        .andExpect(status().isOk());

    assertThat(coordinationStatus(f.coordinationId())).isEqualTo("RESCHEDULE_REQUIRED");
  }

  @Test
  void 확정_이후_상태에서는_제출이_409다() throws Exception {
    Fixture f = fixture();
    for (String statusValue :
        new String[] {"APPLYING", "CONFIRMED", "VISIT_COMPLETED", "CANCELLED"}) {
      setCoordinationStatus(f.coordinationId(), statusValue);
      assertError(
          mockMvc.perform(
              jsonPostAs(
                  submitUrl(f.token()), body(NOTICE_VERSION, slot(f.start(), 10, 12)), null)),
          ErrorCode.INVALID_TRANSITION);
    }
  }

  @Test
  void 취소된_조율도_조회는_가능하고_취소_상태를_알려준다() throws Exception {
    Fixture f = fixture();
    setCoordinationStatus(f.coordinationId(), "CANCELLED");

    mockMvc
        .perform(authedGet(view(f.token()), null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.coordinationStatus").value("CANCELLED"));
  }

  @Test
  void 방문_가능_기간_밖_시간은_422다() throws Exception {
    Fixture f = fixture();

    assertError(
        mockMvc.perform(
            jsonPostAs(
                submitUrl(f.token()),
                body(NOTICE_VERSION, slot(f.start().minusDays(1), 10, 12)),
                null)),
        ErrorCode.TIME_OUT_OF_RANGE);
    assertError(
        mockMvc.perform(
            jsonPostAs(
                submitUrl(f.token()),
                body(NOTICE_VERSION, slot(f.start().plusDays(11), 10, 12)),
                null)),
        ErrorCode.TIME_OUT_OF_RANGE);
    mockMvc
        .perform(
            jsonPostAs(
                submitUrl(f.token()),
                body(NOTICE_VERSION, slot(f.start().plusDays(10), 22, 23)),
                null))
        .andExpect(status().isOk());
    Integer slots =
        jdbcTemplate.queryForObject("SELECT count(*) FROM visit_customer_time_slot", Integer.class);
    assertThat(slots).isEqualTo(1);
  }

  @Test
  void 안내_버전이_시행_버전과_다르면_409이고_아무것도_저장하지_않는다() throws Exception {
    Fixture f = fixture();

    assertError(
        mockMvc.perform(
            jsonPostAs(submitUrl(f.token()), body("0.9", slot(f.start(), 10, 12)), null)),
        ErrorCode.NOTICE_VERSION_CHANGED);

    Integer slots =
        jdbcTemplate.queryForObject("SELECT count(*) FROM visit_customer_time_slot", Integer.class);
    assertThat(slots).isZero();
    assertThat(coordinationStatus(f.coordinationId())).isEqualTo("TIME_COLLECTING");
  }

  @Test
  void 형식이_잘못된_제출은_400이다() throws Exception {
    Fixture f = fixture();

    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                submitUrl(f.token()), "{\"timeSlots\":[" + slot(f.start(), 10, 12) + "]}", null)),
        "acknowledgedNoticeVersion",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(submitUrl(f.token()), "{\"acknowledgedNoticeVersion\":\"1.0\"}", null)),
        "timeSlots",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(submitUrl(f.token()), body(NOTICE_VERSION, slot(f.start(), 12, 10)), null)),
        "timeSlots",
        "INVALID_VALUE");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                submitUrl(f.token()),
                "{\"timeSlots\":[{\"startsAt\":\"bad\",\"endsAt\":\"bad\"}],"
                    + "\"acknowledgedNoticeVersion\":\"1.0\"}",
                null)),
        "timeSlots",
        "INVALID_FORMAT");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                submitUrl(f.token()),
                "{\"timeSlots\":[],\"acknowledgedNoticeVersion\":\"1.0\",\"x\":1}",
                null)),
        "x",
        "NOT_ALLOWED");
    String[] many = new String[51];
    java.util.Arrays.fill(many, slot(f.start(), 10, 12));
    assertFieldError(
        mockMvc.perform(jsonPostAs(submitUrl(f.token()), body(NOTICE_VERSION, many), null)),
        "timeSlots",
        "OUT_OF_RANGE");
  }

  @Test
  void 같은_멱등_키로_같은_내용을_다시_보내면_같은_결과를_돌려주고_새_버전을_만들지_않는다() throws Exception {
    Fixture f = fixture();
    String payload = body(NOTICE_VERSION, slot(f.start(), 10, 12));

    JsonNode first =
        AuthFlowSupport.json(
            mockMvc
                .perform(
                    jsonPostAs(submitUrl(f.token()), payload, null)
                        .header("Idempotency-Key", "0b8f1c9e-5d1b-4f5a-9a0e-1d9a3f7c2b11"))
                .andExpect(status().isOk())
                .andReturn());
    JsonNode retry =
        AuthFlowSupport.json(
            mockMvc
                .perform(
                    jsonPostAs(submitUrl(f.token()), payload, null)
                        .header("Idempotency-Key", "0b8f1c9e-5d1b-4f5a-9a0e-1d9a3f7c2b11"))
                .andExpect(status().isOk())
                .andReturn());

    assertThat(retry.get("responseVersion").intValue()).isEqualTo(1);
    assertThat(retry.get("submittedAt").asString()).isEqualTo(first.get("submittedAt").asString());
    Integer slots =
        jdbcTemplate.queryForObject("SELECT count(*) FROM visit_customer_time_slot", Integer.class);
    assertThat(slots).isEqualTo(1);
  }

  @Test
  void 토큰당_분당_육십_회를_넘으면_429다() throws Exception {
    Fixture f = fixture();

    for (int i = 0; i < 60; i++) {
      mockMvc.perform(authedGet(view(f.token()), null)).andExpect(status().isOk());
    }
    mockMvc
        .perform(authedGet(view(f.token()), null))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
  }

  @Test
  void 토큰_원문은_로그에_남지_않는다() throws Exception {
    Fixture f = fixture();
    Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    root.addAppender(appender);
    try {
      mockMvc.perform(authedGet(view(f.token()), null));
      mockMvc.perform(jsonPostAs(submitUrl(f.token()), body("0.1", slot(f.start(), 10, 12)), null));
      mockMvc.perform(jsonPostAs(submitUrl(f.token()), "{", null));
      mockMvc.perform(
          jsonPostAs(submitUrl(f.token()), body(NOTICE_VERSION, slot(f.start(), 10, 12)), null));
    } finally {
      root.detachAppender(appender);
    }

    assertThat(appender.list)
        .noneMatch(
            event ->
                event.getFormattedMessage().contains(f.token())
                    || String.valueOf(event.getThrowableProxy()).contains(f.token()));
  }

  @Test
  void 다른_조율의_토큰으로는_다른_조율을_볼_수_없고_링크_발급이_사무소_경계를_지킨다() throws Exception {
    Fixture first = fixture();
    Fixture second = fixture();

    mockMvc
        .perform(authedGet(view(first.token()), null))
        .andExpect(jsonPath("$.officeName").value(first.owner().office().getName()));
    mockMvc
        .perform(authedGet(view(second.token()), null))
        .andExpect(jsonPath("$.officeName").value(second.owner().office().getName()));
    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/visit-coordinations/" + second.coordinationId() + "/customer-link/reissue",
                "",
                first.cookie())),
        ErrorCode.RESOURCE_NOT_FOUND);
  }
}
