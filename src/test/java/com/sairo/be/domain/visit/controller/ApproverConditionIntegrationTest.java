package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.ApproverIntegrationTestBase;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ApproverConditionIntegrationTest extends ApproverIntegrationTestBase {

  private record Fixture(
      TestData.Staff owner, Cookie cookie, long propertyId, long approvalId, String token) {}

  private Fixture fixture(String role) throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long propertyId = newProperty(cookie);
    long approvalId = registerApproval(cookie, propertyId, role, 1, 30);
    String token = issueApproverToken(cookie, propertyId, approvalId);
    return new Fixture(owner, cookie, propertyId, approvalId, token);
  }

  private String proxyUrl(Fixture f) {
    return approvalsUrl(f.propertyId()) + "/" + f.approvalId() + "/approver-conditions/proxy";
  }

  private String historyUrl(Fixture f) {
    return approvalsUrl(f.propertyId()) + "/" + f.approvalId() + "/approver-conditions";
  }

  private JsonNode put(Fixture f, String body) throws Exception {
    return AuthFlowSupport.json(
        mockMvc
            .perform(jsonPutAs(conditionsUrl(f.token()), body, null, null))
            .andExpect(status().isOk())
            .andReturn());
  }

  @Test
  void 승인자_링크로_조건을_등록하면_새_버전이_활성이고_안내_확인이_남는다() throws Exception {
    Fixture f = fixture(null);
    String rules =
        "[{\"ruleDate\":\""
            + today().plusDays(5)
            + "\",\"maxVisitsPerDay\":{\"kind\":\"VALUE\",\"value\":1},\"minGapMinutes\":null,"
            + "\"minLeadMinutes\":{\"kind\":\"UNLIMITED\",\"value\":null},"
            + "\"singleVisitMaxMinutes\":null}]";

    mockMvc
        .perform(
            jsonPutAs(
                conditionsUrl(f.token()),
                conditionJson(
                    false, Map.of("dateRules", rules, "groupingPreference", "\"SPREAD\"")),
                null,
                null))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", containsString("no-store")))
        .andExpect(jsonPath("$.versionNo").value(1))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.source").value("DIRECT"))
        .andExpect(jsonPath("$.approvalId").value(f.approvalId()))
        .andExpect(jsonPath("$.maxVisitsPerDay").value(3))
        .andExpect(jsonPath("$.minLeadMinutes").doesNotExist())
        .andExpect(jsonPath("$.groupingPreference").value("SPREAD"))
        .andExpect(jsonPath("$.windows.length()").value(7))
        .andExpect(jsonPath("$.windows[0].startsTime").value("09:00"))
        .andExpect(jsonPath("$.windows[0].isException").value(false))
        .andExpect(jsonPath("$.normalizationApplied").value(false))
        .andExpect(jsonPath("$.dateRules.length()").value(1))
        .andExpect(jsonPath("$.dateRules[0].maxVisitsPerDay.kind").value("VALUE"))
        .andExpect(jsonPath("$.dateRules[0].maxVisitsPerDay.value").value(1))
        .andExpect(jsonPath("$.dateRules[0].minGapMinutes").doesNotExist())
        .andExpect(jsonPath("$.dateRules[0].minLeadMinutes.kind").value("UNLIMITED"))
        .andExpect(jsonPath("$.dateRules[0].minLeadMinutes.value").doesNotExist())
        .andExpect(jsonPath("$.dateRuleReplacedCount").value(0))
        .andExpect(jsonPath("$.enteredViaProfile").value(false))
        .andExpect(jsonPath("$.privacyNotice.noticeCode").value("GUEST_LINK_NOTICE_APPROVER"))
        .andExpect(jsonPath("$.privacyNotice.noticeVersion").value(NOTICE_VERSION))
        .andExpect(jsonPath("$.privacyNotice.retentionDays").value(90));

    Integer acks =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM consent_guest_notice_ack WHERE link_kind = 'APPROVER_PROPERTY'"
                + " AND action_type = 'CONDITION_SAVE' AND terms_version = ? AND resource_version"
                + " = 1 AND office_id = ?",
            Integer.class,
            NOTICE_VERSION,
            f.owner().office().getId());
    assertThat(acks).isEqualTo(1);
    Integer ruleRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM visit_approver_condition_date_rule WHERE condition_id = ?",
            Integer.class,
            activeConditionId(f.approvalId()));
    assertThat(ruleRows).isEqualTo(2);
  }

  @Test
  void 다시_등록하면_이전_버전은_대체되고_이력은_최신이_앞이다() throws Exception {
    Fixture f = fixture(null);
    put(f, conditionJson(false));
    put(f, conditionJson(false, Map.of("maxVisitsPerDay", "5")));

    mockMvc
        .perform(authedGet(conditionsUrl(f.token()), null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.versionNo").value(2))
        .andExpect(jsonPath("$.maxVisitsPerDay").value(5));
    mockMvc
        .perform(authedGet(historyUrl(f), f.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].versionNo").value(2))
        .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
        .andExpect(jsonPath("$.items[0].supersededAt").doesNotExist())
        .andExpect(jsonPath("$.items[1].versionNo").value(1))
        .andExpect(jsonPath("$.items[1].status").value("SUPERSEDED"))
        .andExpect(jsonPath("$.items[1].supersededAt").exists())
        .andExpect(jsonPath("$.items[1].windows.length()").value(7))
        .andExpect(jsonPath("$.items[1].dateRules.length()").value(0))
        .andExpect(jsonPath("$.items[1].enteredViaProfile").value(false));
  }

  @Test
  void 활성_조건이_없으면_조회는_404이고_개인정보_안내_버전이_다르면_저장하지_않는다() throws Exception {
    Fixture f = fixture(null);
    assertError(
        mockMvc.perform(authedGet(conditionsUrl(f.token()), null)), ErrorCode.RESOURCE_NOT_FOUND);

    assertError(
        mockMvc.perform(
            jsonPutAs(
                conditionsUrl(f.token()),
                conditionJson(false, Map.of("acknowledgedNoticeVersion", "\"0.1\"")),
                null,
                null)),
        ErrorCode.NOTICE_VERSION_CHANGED);

    Integer rows =
        jdbcTemplate.queryForObject("SELECT count(*) FROM visit_approver_condition", Integer.class);
    assertThat(rows).isZero();
  }

  @Test
  void 입력_검증_실패는_400이다() throws Exception {
    Fixture f = fixture(null);
    String url = conditionsUrl(f.token());
    Map<String, String[]> cases = new LinkedHashMap<>();
    cases.put(
        "appliesUntil",
        new String[] {"\"" + today().minusDays(2) + "T00:00:00+09:00\"", "INVALID_VALUE"});
    cases.put("maxVisitsPerDay", new String[] {"21", "OUT_OF_RANGE"});
    cases.put("minGapMinutes", new String[] {"-1", "OUT_OF_RANGE"});
    cases.put("singleVisitMaxMinutes", new String[] {"0", "OUT_OF_RANGE"});
    cases.put("minLeadMinutes", new String[] {"\"3\"", "TYPE_MISMATCH"});
    cases.put("groupingPreference", new String[] {"\"MIXED\"", "NOT_ALLOWED"});
    cases.put("windows", new String[] {null, "REQUIRED"});
    cases.put("impactAcknowledged", new String[] {null, "REQUIRED"});
    cases.put("acknowledgedNoticeVersion", new String[] {null, "REQUIRED"});
    for (Map.Entry<String, String[]> entry : cases.entrySet()) {
      Map<String, String> override = new LinkedHashMap<>();
      override.put(entry.getKey(), entry.getValue()[0]);
      assertFieldError(
          mockMvc.perform(jsonPutAs(url, conditionJson(false, override), null, null)),
          entry.getKey(),
          entry.getValue()[1]);
    }
    assertFieldError(
        mockMvc.perform(
            jsonPutAs(url, conditionJson(false, Map.of("source", "\"PROXY\"")), null, null)),
        "source",
        "NOT_ALLOWED");
  }

  private static String window(
      String type, String extra, String start, String end, String exception) {
    return "[{\"windowType\":\""
        + type
        + "\""
        + extra
        + ",\"startsTime\":\""
        + start
        + "\",\"endsTime\":\""
        + end
        + "\""
        + exception
        + "}]";
  }

  @Test
  void 시간대_규칙_위반은_400이다() throws Exception {
    Fixture f = fixture(null);
    String url = conditionsUrl(f.token());
    String off = ",\"isException\":false";
    String[][] cases = {
      {
        window("WEEKDAY", ",\"weekday\":8", "09:00", "10:00", off),
        "windows[0].weekday",
        "OUT_OF_RANGE"
      },
      {
        window("WEEKDAY", ",\"weekday\":1,\"windowDate\":\"2030-01-01\"", "09:00", "10:00", off),
        "windows[0].windowDate",
        "NOT_ALLOWED"
      },
      {
        window("DATE", ",\"weekday\":1,\"windowDate\":\"2030-01-01\"", "09:00", "10:00", off),
        "windows[0].weekday",
        "NOT_ALLOWED"
      },
      {window("DATE", "", "09:00", "10:00", off), "windowDate", "REQUIRED"},
      {
        window("WEEKDAY", ",\"weekday\":1", "10:00", "10:00", off),
        "windows[0].endsTime",
        "INVALID_VALUE"
      },
      {
        window("WEEKDAY", ",\"weekday\":1", "9:00", "10:00", off),
        "windows[0].startsTime",
        "INVALID_FORMAT"
      },
      {
        window("WEEKDAY", ",\"weekday\":1", "24:00", "24:00", off),
        "windows[0].startsTime",
        "INVALID_FORMAT"
      },
      {window("WEEKDAY", ",\"weekday\":1", "09:00", "10:00", ""), "isException", "REQUIRED"}
    };
    for (String[] testCase : cases) {
      assertFieldError(
          mockMvc.perform(
              jsonPutAs(url, conditionJson(false, Map.of("windows", testCase[0])), null, null)),
          testCase[1],
          testCase[2]);
    }
  }

  @Test
  void 날짜별_필수_조건_검증은_400이다() throws Exception {
    Fixture f = fixture(null);
    String url = conditionsUrl(f.token());
    String inRange = today().plusDays(5).toString();
    String outOfRange = today().plusDays(90).toString();
    assertFieldError(
        mockMvc.perform(
            jsonPutAs(
                url,
                conditionJson(
                    false,
                    Map.of(
                        "dateRules",
                        "[{\"ruleDate\":\""
                            + inRange
                            + "\",\"maxVisitsPerDay\":null,\"minGapMinutes\":null,"
                            + "\"minLeadMinutes\":null,\"singleVisitMaxMinutes\":null}]")),
                null,
                null)),
        "dateRules[0].ruleDate",
        "INVALID_VALUE");
    assertFieldError(
        mockMvc.perform(
            jsonPutAs(
                url,
                conditionJson(
                    false,
                    Map.of(
                        "dateRules",
                        "[{\"ruleDate\":\""
                            + outOfRange
                            + "\",\"maxVisitsPerDay\":{\"kind\":\"VALUE\",\"value\":2}}]")),
                null,
                null)),
        "dateRules[0].ruleDate",
        "OUT_OF_RANGE");
    assertFieldError(
        mockMvc.perform(
            jsonPutAs(
                url,
                conditionJson(
                    false,
                    Map.of(
                        "dateRules",
                        "[{\"ruleDate\":\""
                            + inRange
                            + "\",\"maxVisitsPerDay\":{\"kind\":\"VALUE\",\"value\":0}}]")),
                null,
                null)),
        "dateRules[0].maxVisitsPerDay.value",
        "OUT_OF_RANGE");
    assertFieldError(
        mockMvc.perform(
            jsonPutAs(
                url,
                conditionJson(
                    false,
                    Map.of(
                        "dateRules",
                        "[{\"ruleDate\":\""
                            + inRange
                            + "\",\"minGapMinutes\":{\"kind\":\"UNLIMITED\",\"value\":5}}]")),
                null,
                null)),
        "dateRules[0].minGapMinutes.value",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(
            jsonPutAs(
                url,
                conditionJson(
                    false,
                    Map.of(
                        "dateRules",
                        "[{\"ruleDate\":\""
                            + inRange
                            + "\",\"minGapMinutes\":{\"kind\":\"VALUE\"}}]")),
                null,
                null)),
        "dateRules[0].minGapMinutes.value",
        "REQUIRED");
  }

  @Test
  void 겹치거나_맞닿는_구간은_합쳐_저장하고_합쳐진_구간을_알린다() throws Exception {
    Fixture f = fixture(null);
    String windows =
        "[{\"windowType\":\"WEEKDAY\",\"weekday\":2,\"startsTime\":\"09:00\",\"endsTime\":\"11:00\",\"isException\":false},"
            + "{\"windowType\":\"WEEKDAY\",\"weekday\":2,\"startsTime\":\"11:00\",\"endsTime\":\"12:00\",\"isException\":false},"
            + "{\"windowType\":\"WEEKDAY\",\"weekday\":2,\"startsTime\":\"11:30\",\"endsTime\":\"13:00\",\"isException\":false},"
            + "{\"windowType\":\"WEEKDAY\",\"weekday\":2,\"startsTime\":\"15:00\",\"endsTime\":\"16:00\",\"isException\":false},"
            + "{\"windowType\":\"DATE\",\"windowDate\":\""
            + today().plusDays(3)
            + "\",\"startsTime\":\"00:00\",\"endsTime\":\"24:00\",\"isException\":true}]";

    mockMvc
        .perform(
            jsonPutAs(
                conditionsUrl(f.token()),
                conditionJson(false, Map.of("windows", windows)),
                null,
                null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.normalizationApplied").value(true))
        .andExpect(jsonPath("$.normalizedRanges.length()").value(1))
        .andExpect(jsonPath("$.normalizedRanges[0].weekday").value(2))
        .andExpect(jsonPath("$.normalizedRanges[0].startsTime").value("09:00"))
        .andExpect(jsonPath("$.normalizedRanges[0].endsTime").value("13:00"))
        .andExpect(jsonPath("$.windows.length()").value(3))
        .andExpect(jsonPath("$.windows[?(@.windowType == 'DATE')].endsTime").value("24:00"))
        .andExpect(jsonPath("$.windows[?(@.windowType == 'DATE')].isException").value(true));

    Integer stored =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM visit_approver_condition_window WHERE condition_id = ?",
            Integer.class,
            activeConditionId(f.approvalId()));
    assertThat(stored).isEqualTo(3);
  }

  @Test
  void 같은_날짜를_다시_넣으면_마지막_입력이_칸_단위로_대체되고_대체_수를_알린다() throws Exception {
    Fixture f = fixture(null);
    String date = today().plusDays(7).toString();
    String rules =
        "[{\"ruleDate\":\""
            + date
            + "\",\"maxVisitsPerDay\":{\"kind\":\"VALUE\",\"value\":1},\"minGapMinutes\":{\"kind\":\"VALUE\",\"value\":10}},"
            + "{\"ruleDate\":\""
            + date
            + "\",\"maxVisitsPerDay\":{\"kind\":\"VALUE\",\"value\":2}}]";

    mockMvc
        .perform(
            jsonPutAs(
                conditionsUrl(f.token()),
                conditionJson(false, Map.of("dateRules", rules)),
                null,
                null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dateRules.length()").value(1))
        .andExpect(jsonPath("$.dateRules[0].maxVisitsPerDay.value").value(2))
        .andExpect(jsonPath("$.dateRules[0].minGapMinutes.value").value(10))
        .andExpect(jsonPath("$.dateRuleReplacedCount").value(1));
  }

  @Test
  void 같은_멱등_키로_같은_내용을_다시_보내면_새_버전과_안내_확인을_만들지_않는다() throws Exception {
    Fixture f = fixture(null);
    String body = conditionJson(false);

    for (int i = 0; i < 2; i++) {
      mockMvc
          .perform(
              jsonPutAs(conditionsUrl(f.token()), body, null, null)
                  .header("Idempotency-Key", "k-1"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.versionNo").value(1));
    }

    Integer versions =
        jdbcTemplate.queryForObject("SELECT count(*) FROM visit_approver_condition", Integer.class);
    Integer acks =
        jdbcTemplate.queryForObject("SELECT count(*) FROM consent_guest_notice_ack", Integer.class);
    assertThat(versions).isEqualTo(1);
    assertThat(acks).isEqualTo(1);
  }

  @Test
  void 동시에_저장해도_활성_버전은_하나이고_버전_번호는_이어진다() throws Exception {
    Fixture f = fixture(null);
    int threads = 4;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      String body = conditionJson(false, Map.of("maxVisitsPerDay", String.valueOf(i + 1)));
      Callable<Integer> task =
          () -> {
            start.await();
            return mockMvc
                .perform(jsonPutAs(conditionsUrl(f.token()), body, null, null))
                .andReturn()
                .getResponse()
                .getStatus();
          };
      results.add(pool.submit(task));
    }
    start.countDown();
    for (Future<Integer> result : results) {
      assertThat(result.get()).isEqualTo(200);
    }
    pool.shutdown();

    List<Integer> versions =
        jdbcTemplate.queryForList(
            "SELECT version_no FROM visit_approver_condition WHERE approval_id = ? ORDER BY"
                + " version_no",
            Integer.class,
            f.approvalId());
    assertThat(versions).containsExactly(1, 2, 3, 4);
    Integer active =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM visit_approver_condition WHERE approval_id = ? AND status ="
                + " 'ACTIVE'",
            Integer.class,
            f.approvalId());
    assertThat(active).isEqualTo(1);
  }

  @Test
  void 공동_승인자_링크는_대표_조건을_읽기만_하고_쓰기는_403이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long propertyId = newProperty(cookie);
    long primary = registerApproval(cookie, propertyId, "PRIMARY", -1, 30);
    long co = registerApproval(cookie, propertyId, "CO_APPROVER", -1, 30);
    String primaryToken = issueApproverToken(cookie, propertyId, primary);
    String coToken = issueApproverToken(cookie, propertyId, co);
    mockMvc
        .perform(jsonPutAs(conditionsUrl(primaryToken), conditionJson(false), null, null))
        .andExpect(status().isOk());

    mockMvc
        .perform(authedGet(conditionsUrl(coToken), null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approvalId").value(primary))
        .andExpect(jsonPath("$.versionNo").value(1));
    assertError(
        mockMvc.perform(jsonPutAs(conditionsUrl(coToken), conditionJson(false), null, null)),
        ErrorCode.APPROVER_READ_ONLY);
    assertError(
        mockMvc.perform(
            jsonPostAs(conditionsUrl(coToken) + "/impact-preview", conditionJson(false), null)),
        ErrorCode.APPROVER_READ_ONLY);
    jdbcTemplate.update(
        "UPDATE property_access_approval SET revoked_at = now(), revocation_reason = '철회',"
            + " revocation_recorded_at = now(), revoked_by_user_id = ? WHERE id = ?",
        owner.user().getId(),
        primary);
    assertError(
        mockMvc.perform(authedGet(conditionsUrl(coToken), null)), ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 공개_링크_조건_경로는_잘못된_만료_폐기_토큰을_404로_막는다() throws Exception {
    Fixture f = fixture(null);
    assertError(
        mockMvc.perform(authedGet(conditionsUrl("no-such-token"), null)),
        ErrorCode.PUBLIC_LINK_NOT_FOUND);
    assertError(
        mockMvc.perform(
            jsonPutAs(conditionsUrl("no-such-token"), conditionJson(false), null, null)),
        ErrorCode.PUBLIC_LINK_NOT_FOUND);
    assertError(
        mockMvc.perform(
            jsonPostAs(
                conditionsUrl("no-such-token") + "/impact-preview", conditionJson(false), null)),
        ErrorCode.PUBLIC_LINK_NOT_FOUND);

    jdbcTemplate.update(
        "UPDATE visit_approver_property_link SET expires_at = now() - interval '1 minute' WHERE"
            + " approval_id = ?",
        f.approvalId());
    assertError(
        mockMvc.perform(authedGet(conditionsUrl(f.token()), null)), ErrorCode.PUBLIC_LINK_EXPIRED);
    assertError(
        mockMvc.perform(jsonPutAs(conditionsUrl(f.token()), conditionJson(false), null, null)),
        ErrorCode.PUBLIC_LINK_EXPIRED);
    assertError(
        mockMvc.perform(
            jsonPostAs(conditionsUrl(f.token()) + "/impact-preview", conditionJson(false), null)),
        ErrorCode.PUBLIC_LINK_EXPIRED);
  }

  @Test
  void 철회된_출입승인에는_조건을_저장할_수_없다() throws Exception {
    Fixture f = fixture(null);
    jdbcTemplate.update(
        "UPDATE property_access_approval SET revoked_at = now(), revocation_reason = '철회',"
            + " revocation_recorded_at = now(), revoked_by_user_id = ? WHERE id = ?",
        f.owner().user().getId(),
        f.approvalId());

    assertError(
        mockMvc.perform(jsonPutAs(conditionsUrl(f.token()), conditionJson(false), null, null)),
        ErrorCode.INVALID_TRANSITION);
  }

  private record Confirmed(
      Fixture fixture, long coordinationId, long scheduleItemId, TestData.Staff owner) {}

  private Confirmed confirmedVisit(int hourStart, int hourEnd) throws Exception {
    Fixture f = fixture(null);
    put(f, conditionJson(false));
    long customer =
        insertCustomer(f.owner().office().getId(), f.owner().membership().getId(), "이수요");
    long coordinationId =
        createCoordination(
                f.cookie(),
                customer,
                null,
                today().plusDays(1),
                today().plusDays(20),
                f.propertyId())
            .get("coordinationId")
            .longValue();
    long itemId =
        insertConfirmedVisit(
            new TestStaffRef(f.owner().office().getId(), f.owner().membership().getId()),
            coordinationId,
            f.propertyId(),
            f.approvalId(),
            today().plusDays(5),
            hourStart,
            hourEnd);
    return new Confirmed(f, coordinationId, itemId, f.owner());
  }

  @Test
  void 영향_미리보기는_저장하지_않고_위반_규칙을_알린다() throws Exception {
    Confirmed c = confirmedVisit(10, 12);
    String windows =
        "[{\"windowType\":\"WEEKDAY\",\"weekday\":1,\"startsTime\":\"13:00\",\"endsTime\":\"18:00\",\"isException\":false},"
            + "{\"windowType\":\"WEEKDAY\",\"weekday\":2,\"startsTime\":\"13:00\",\"endsTime\":\"18:00\",\"isException\":false},"
            + "{\"windowType\":\"WEEKDAY\",\"weekday\":3,\"startsTime\":\"13:00\",\"endsTime\":\"18:00\",\"isException\":false},"
            + "{\"windowType\":\"WEEKDAY\",\"weekday\":4,\"startsTime\":\"13:00\",\"endsTime\":\"18:00\",\"isException\":false},"
            + "{\"windowType\":\"WEEKDAY\",\"weekday\":5,\"startsTime\":\"13:00\",\"endsTime\":\"18:00\",\"isException\":false},"
            + "{\"windowType\":\"WEEKDAY\",\"weekday\":6,\"startsTime\":\"13:00\",\"endsTime\":\"18:00\",\"isException\":false},"
            + "{\"windowType\":\"WEEKDAY\",\"weekday\":7,\"startsTime\":\"13:00\",\"endsTime\":\"18:00\",\"isException\":false}]";

    mockMvc
        .perform(
            jsonPostAs(
                conditionsUrl(c.fixture().token()) + "/impact-preview",
                conditionJson(false, Map.of("windows", windows, "singleVisitMaxMinutes", "60")),
                null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasImpact").value(true))
        .andExpect(jsonPath("$.affectedItems.length()").value(1))
        .andExpect(jsonPath("$.affectedItems[0].scheduleItemId").value(c.scheduleItemId()))
        .andExpect(
            jsonPath("$.affectedItems[0].violatedRules")
                .value(org.hamcrest.Matchers.contains("WINDOW_MISMATCH", "SINGLE_VISIT_MAX")))
        .andExpect(jsonPath("$.affectedItems[0].scheduledStartAt").exists());

    assertThat(activeVersion(c.fixture().approvalId())).isEqualTo(1);
    assertThat(coordinationStatus(c.coordinationId())).isEqualTo("CONFIRMED");
  }

  @Test
  void 영향이_없는_변경은_확인_없이_반영하고_영향_안내는_빈_배열이다() throws Exception {
    Confirmed c = confirmedVisit(10, 11);

    mockMvc
        .perform(
            jsonPostAs(
                conditionsUrl(c.fixture().token()) + "/impact-preview", conditionJson(false), null))
        .andExpect(jsonPath("$.hasImpact").value(false))
        .andExpect(jsonPath("$.affectedItems.length()").value(0));
    mockMvc
        .perform(
            jsonPutAs(
                conditionsUrl(c.fixture().token()),
                conditionJson(false, Map.of("maxVisitsPerDay", "5")),
                null,
                null))
        .andExpect(status().isOk());

    assertThat(coordinationStatus(c.coordinationId())).isEqualTo("CONFIRMED");
  }

  @Test
  void 영향이_있으면_확인_없이는_409이고_확인하면_재조율_필요로_표시하고_알림을_남긴다() throws Exception {
    Confirmed c = confirmedVisit(10, 11);
    String dateRule =
        "[{\"ruleDate\":\""
            + today().plusDays(5)
            + "\",\"singleVisitMaxMinutes\":{\"kind\":\"VALUE\",\"value\":30}}]";
    String body = conditionJson(false, Map.of("dateRules", dateRule));

    mockMvc
        .perform(jsonPutAs(conditionsUrl(c.fixture().token()), body, null, null))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IMPACT_NOT_ACKNOWLEDGED"))
        .andExpect(jsonPath("$.affectedItems[0].scheduleItemId").value(c.scheduleItemId()))
        .andExpect(jsonPath("$.affectedItems[0].violatedRules[0]").value("SINGLE_VISIT_MAX"));
    assertThat(activeVersion(c.fixture().approvalId())).isEqualTo(1);
    assertThat(coordinationStatus(c.coordinationId())).isEqualTo("CONFIRMED");

    mockMvc
        .perform(
            jsonPutAs(
                conditionsUrl(c.fixture().token()),
                body.replace("\"impactAcknowledged\":false", "\"impactAcknowledged\":true"),
                null,
                null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.versionNo").value(2));

    assertThat(coordinationStatus(c.coordinationId())).isEqualTo("RESCHEDULE_REQUIRED");
    Map<String, Object> alert =
        jdbcTemplate.queryForMap(
            "SELECT reschedule_trigger_type, reschedule_reason FROM visit_coordination WHERE id = ?",
            c.coordinationId());
    assertThat(alert.get("reschedule_trigger_type")).isEqualTo("SCHEDULE_CHANGED");
    assertThat(alert.get("reschedule_reason")).isNotNull();
    Integer history =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM visit_coordination_status_history WHERE coordination_id = ? AND"
                + " to_status = 'RESCHEDULE_REQUIRED' AND source = 'APPROVER'",
            Integer.class,
            c.coordinationId());
    assertThat(history).isEqualTo(1);
    Integer notices =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM visit_request_notice WHERE notice_type ="
                + " 'CONDITION_CHANGE_IMPACT' AND recipient_membership_id = ?",
            Integer.class,
            c.owner().membership().getId());
    assertThat(notices).isEqualTo(1);
    Integer scheduleStillScheduled =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM visit_schedule_item WHERE id = ? AND result = 'SCHEDULED'",
            Integer.class,
            c.scheduleItemId());
    assertThat(scheduleStillScheduled).isEqualTo(1);
  }

  @Test
  void 적용_기간_밖으로_밀려난_확정_방문은_조건_만료로_알린다() throws Exception {
    Confirmed c = confirmedVisit(10, 11);
    String body =
        conditionJson(
            false, Map.of("appliesUntil", "\"" + today().plusDays(3) + "T23:59:59+09:00\""));

    mockMvc
        .perform(jsonPostAs(conditionsUrl(c.fixture().token()) + "/impact-preview", body, null))
        .andExpect(jsonPath("$.hasImpact").value(true))
        .andExpect(jsonPath("$.affectedItems[0].violatedRules[0]").value("CONDITION_EXPIRED"));
  }

  private int activeVersion(long approvalId) {
    Integer version =
        jdbcTemplate.queryForObject(
            "SELECT version_no FROM visit_approver_condition WHERE approval_id = ? AND status ="
                + " 'ACTIVE'",
            Integer.class,
            approvalId);
    return version == null ? -1 : version;
  }

  @Test
  void 대리_입력은_대리_출처로_기록하고_본인_입력이_활성이면_409다() throws Exception {
    Fixture f = fixture(null);

    mockMvc
        .perform(jsonPutAs(proxyUrl(f), conditionJson(true), f.cookie(), null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("PROXY"))
        .andExpect(jsonPath("$.versionNo").value(1))
        .andExpect(jsonPath("$.privacyNotice.noticeCode").exists());
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT source, recorded_by_membership_id FROM visit_approver_condition WHERE"
                + " approval_id = ?",
            f.approvalId());
    assertThat(row.get("source")).isEqualTo("PROXY");
    assertThat(((Number) row.get("recorded_by_membership_id")).longValue())
        .isEqualTo(f.owner().membership().getId());
    Integer acks =
        jdbcTemplate.queryForObject("SELECT count(*) FROM consent_guest_notice_ack", Integer.class);
    assertThat(acks).isZero();

    put(f, conditionJson(false, Map.of("maxVisitsPerDay", "4")));
    mockMvc
        .perform(authedGet(conditionsUrl(f.token()), null))
        .andExpect(jsonPath("$.source").value("DIRECT"))
        .andExpect(jsonPath("$.versionNo").value(2));
    assertError(
        mockMvc.perform(jsonPutAs(proxyUrl(f), conditionJson(true), f.cookie(), null)),
        ErrorCode.INVALID_TRANSITION);
    mockMvc
        .perform(authedGet(historyUrl(f), f.cookie()))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].source").value("DIRECT"))
        .andExpect(jsonPath("$.items[1].source").value("PROXY"));
  }

  @Test
  void 대리_입력은_본문의_출처_승인_번호_안내_버전을_받지_않는다() throws Exception {
    Fixture f = fixture(null);
    for (String key : new String[] {"source", "approvalId", "acknowledgedNoticeVersion"}) {
      assertFieldError(
          mockMvc.perform(
              jsonPutAs(proxyUrl(f), conditionJson(true, Map.of(key, "\"1\"")), f.cookie(), null)),
          key,
          "NOT_ALLOWED");
    }
  }

  @Test
  void 공동_승인자_출입승인에는_대리_입력도_409다() throws Exception {
    Fixture f = fixture("CO_APPROVER");

    assertError(
        mockMvc.perform(jsonPutAs(proxyUrl(f), conditionJson(true), f.cookie(), null)),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 대리_입력도_영향_확인_전에는_409이고_영향_방문을_알린다() throws Exception {
    Confirmed c = confirmedVisit(10, 11);
    jdbcTemplate.update(
        "UPDATE visit_approver_condition SET source = 'PROXY', recorded_by_membership_id = ? WHERE"
            + " approval_id = ?",
        c.owner().membership().getId(),
        c.fixture().approvalId());
    String dateRule =
        "[{\"ruleDate\":\""
            + today().plusDays(5)
            + "\",\"singleVisitMaxMinutes\":{\"kind\":\"VALUE\",\"value\":30}}]";
    String proxyUrl = proxyUrl(c.fixture());

    mockMvc
        .perform(
            jsonPutAs(
                proxyUrl,
                conditionJson(true, Map.of("dateRules", dateRule)),
                c.fixture().cookie(),
                null))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IMPACT_NOT_ACKNOWLEDGED"))
        .andExpect(jsonPath("$.affectedItems[0].scheduleItemId").value(c.scheduleItemId()));
    mockMvc
        .perform(
            jsonPutAs(
                proxyUrl,
                conditionJson(true, Map.of("dateRules", dateRule, "impactAcknowledged", "true")),
                c.fixture().cookie(),
                null))
        .andExpect(status().isOk());

    assertThat(coordinationStatus(c.coordinationId())).isEqualTo("RESCHEDULE_REQUIRED");
    Integer history =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM visit_coordination_status_history WHERE coordination_id = ? AND"
                + " to_status = 'RESCHEDULE_REQUIRED' AND source = 'STAFF' AND actor_membership_id"
                + " = ?",
            Integer.class,
            c.coordinationId(),
            c.owner().membership().getId());
    assertThat(history).isEqualTo(1);
  }

  @Test
  void 대리_입력과_이력_엔드포인트는_401_403_다른_사무소_404_중개보조원_권한을_지킨다() throws Exception {
    Fixture f = fixture(null);
    var foreign = newStaff(OfficeMembershipRole.OWNER);
    var deputy = staffIn(f.owner().office(), OfficeMembershipRole.DEPUTY);
    var agent = staffIn(f.owner().office(), OfficeMembershipRole.AGENT);
    Cookie plain = kakaoSessionFor(saveEmailUser("nomember4@example.com", "010-1212-3434"));
    String body = conditionJson(true);

    assertError(
        mockMvc.perform(jsonPutAs(proxyUrl(f), body, null, null)), ErrorCode.UNAUTHENTICATED);
    assertError(mockMvc.perform(authedGet(historyUrl(f), null)), ErrorCode.UNAUTHENTICATED);
    assertError(mockMvc.perform(jsonPutAs(proxyUrl(f), body, plain, null)), ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(authedGet(historyUrl(f), plain)), ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(jsonPutAs(proxyUrl(f), body, cookieOf(deputy), null)), ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(jsonPutAs(proxyUrl(f), body, cookieOf(foreign), null)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(authedGet(historyUrl(f), cookieOf(foreign))), ErrorCode.RESOURCE_NOT_FOUND);
    long otherProperty = newProperty(f.cookie());
    String mismatched = approvalsUrl(otherProperty) + "/" + f.approvalId() + "/approver-conditions";
    assertError(mockMvc.perform(authedGet(mismatched, f.cookie())), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(jsonPutAs(mismatched + "/proxy", body, f.cookie(), null)),
        ErrorCode.RESOURCE_NOT_FOUND);

    mockMvc.perform(authedGet(historyUrl(f), cookieOf(deputy))).andExpect(status().isOk());
    mockMvc.perform(jsonPutAs(proxyUrl(f), body, cookieOf(agent), null)).andExpect(status().isOk());
  }
}
