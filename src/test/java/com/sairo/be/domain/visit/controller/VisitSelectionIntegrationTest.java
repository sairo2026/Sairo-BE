package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitSelectionTestBase;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class VisitSelectionIntegrationTest extends VisitSelectionTestBase {

  private static final Pattern FIELD_NAME = Pattern.compile("\"([A-Za-z]+)\"\\s*:");
  private static final Pattern SCORE_WORD =
      Pattern.compile(
          "(?i:^(score|rating|rank|stars?|grade)$)|[a-z](Score|Rating|Rank|Stars?|Grade)(?![a-z])");

  private static boolean hasScoreField(String raw) {
    var matcher = FIELD_NAME.matcher(raw);
    while (matcher.find()) {
      String name = matcher.group(1);
      if (SCORE_WORD.matcher(name).find()) {
        return true;
      }
    }
    return false;
  }

  private JsonNode json(org.springframework.test.web.servlet.ResultActions actions)
      throws Exception {
    return AuthFlowSupport.json(actions.andReturn());
  }

  private String windows(String from, String to) {
    return IntStream.rangeClosed(1, 7)
        .mapToObj(
            day ->
                "{\"windowType\":\"WEEKDAY\",\"weekday\":"
                    + day
                    + ",\"windowDate\":null,\"startsTime\":\""
                    + from
                    + "\",\"endsTime\":\""
                    + to
                    + "\",\"isException\":false}")
        .collect(Collectors.joining(",", "[", "]"));
  }

  private void changeCondition(RequestFixture f, Map<String, String> overrides) throws Exception {
    mockMvc
        .perform(
            jsonPutAs(
                approvalsUrl(f.propertyId()) + "/" + f.approvalId() + "/approver-conditions/proxy",
                conditionJson(true, overrides),
                f.cookie(),
                null))
        .andExpect(status().isOk());
  }

  private long count(String sql, Object... args) {
    Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
    return value == null ? -1 : value;
  }

  @Test
  void 비교_목록은_신청_대기만_담고_고객_정보가_없으며_신청_version과_안내를_담는다() throws Exception {
    SelectionFixture s = selectionFixture();

    var result =
        compare(s.token())
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(jsonPath("$.approvalRole").value("PRIMARY"))
            .andExpect(jsonPath("$.activeConditionVersionNo").value(1))
            .andExpect(jsonPath("$.trustPolicyVersion").value("TRUST_V1"))
            .andExpect(jsonPath("$.unconfirmedIndicators.length()").value(3))
            .andExpect(jsonPath("$.grantedOfficeNames.length()").value(0))
            .andExpect(jsonPath("$.privacyNotice.noticeVersion").value(NOTICE_VERSION))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].requestId").value(s.requestId()))
            .andExpect(jsonPath("$.items[0].status").value("PENDING"))
            .andExpect(jsonPath("$.items[0].version").value(1))
            .andExpect(jsonPath("$.items[0].requestKind").value("NEW"))
            .andExpect(jsonPath("$.items[0].applicantRelation").value("OWNER_OFFICE"))
            .andExpect(jsonPath("$.items[0].isPreferredBroker").value(false))
            .andExpect(jsonPath("$.items[0].applicantHistory.previousCompletedVisitCount").value(0))
            .andExpect(jsonPath("$.items[0].applicantTrust.state").value("NO_DATA"))
            .andExpect(jsonPath("$.items[0].applicantTrust.indicators.length()").value(3))
            .andReturn();

    String raw = result.getResponse().getContentAsString();
    assertThat(raw).doesNotContain("이수요");
    assertThat(raw).doesNotContain("customerName").doesNotContain("customerPhone");
    assertThat(Pattern.compile("\\d{3}-\\d{3,4}-\\d{4}").matcher(raw).find()).isFalse();
  }

  @Test
  void 비교_목록과_선택_응답에는_점수_별점_순위_필드가_없다() throws Exception {
    SelectionFixture s = selectionFixture();
    var agentCookie = cookieOf(staffIn(s.base().owner().office(), OfficeMembershipRole.AGENT));
    secondRequest(s.base(), agentCookie, "11:00", "11:30", 9, 17);

    String listRaw = compare(s.token()).andReturn().getResponse().getContentAsString();
    assertThat(hasScoreField(listRaw)).isFalse();

    String selectRaw =
        selectNow(s.token(), s.requestId()).andReturn().getResponse().getContentAsString();
    assertThat(hasScoreField(selectRaw)).isFalse();
  }

  @Test
  void 선호_표시는_위쪽_묶음으로_보일_뿐_선택은_어느_신청이든_같다() throws Exception {
    SelectionFixture s = selectionFixture();
    var agent = staffIn(s.base().owner().office(), OfficeMembershipRole.AGENT);
    SecondRequest second = secondRequest(s.base(), cookieOf(agent), "11:00", "11:30", 9, 17);
    Long linkId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM visit_approver_property_link WHERE approval_id = ?",
            Long.class,
            s.base().approvalId());
    jdbcTemplate.update(
        "INSERT INTO visit_preferred_broker (approval_id, broker_membership_id,"
            + " designated_via_link_id, source_request_id) VALUES (?, ?, ?, ?)",
        s.base().approvalId(),
        agent.membership().getId(),
        linkId,
        second.requestId());

    compare(s.token())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].requestId").value(second.requestId()))
        .andExpect(jsonPath("$.items[0].isPreferredBroker").value(true))
        .andExpect(jsonPath("$.items[1].requestId").value(s.requestId()));

    selectNow(s.token(), s.requestId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestStatus").value("CONFIRMED"));
    assertThat(requestStatus(second.requestId())).isEqualTo("PENDING");
  }

  @Test
  void 신뢰_요약은_표본이_부족하면_구간을_숨기고_표시_순서는_제출_시각을_따른다() throws Exception {
    SelectionFixture s = selectionFixture();
    var agent = staffIn(s.base().owner().office(), OfficeMembershipRole.AGENT);
    SecondRequest second = secondRequest(s.base(), cookieOf(agent), "11:00", "11:30", 9, 17);
    SecondRequest history = secondRequest(s.base(), s.cookie(), "14:00", "14:30", 9, 17);
    requestToCompleted(s, history);

    compare(s.token())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].requestId").value(s.requestId()))
        .andExpect(jsonPath("$.items[1].requestId").value(second.requestId()))
        .andExpect(jsonPath("$.items[0].applicantTrust.state").value("INSUFFICIENT"))
        .andExpect(jsonPath("$.items[0].applicantTrust.completedVisitCount").value(1))
        .andExpect(jsonPath("$.items[0].applicantTrust.evaluatedVisitCount").value(1))
        .andExpect(jsonPath("$.items[0].applicantTrust.overallBand").doesNotExist())
        .andExpect(jsonPath("$.items[0].applicantTrust.indicators[0].answeredCount").doesNotExist())
        .andExpect(jsonPath("$.items[1].applicantTrust.state").value("NO_DATA"))
        .andExpect(jsonPath("$.items[0].applicantHistory.previousCompletedVisitCount").value(1));
  }

  private void requestToCompleted(SelectionFixture s, SecondRequest history) {
    long linkId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM visit_approver_property_link WHERE approval_id = ?",
            Long.class,
            s.base().approvalId());
    jdbcTemplate.update(
        "UPDATE visit_request SET status = 'CONFIRMED', closed_at = NULL WHERE id = ?",
        history.requestId());
    long itemId =
        insertBusyItem(
            history.coordinationId(),
            history.candidateId(),
            s.base().owner().office().getId(),
            today().minusDays(2),
            "10:00",
            "10:30",
            null);
    jdbcTemplate.update(
        "UPDATE visit_schedule_item SET result = 'COMPLETED', actual_visited_at ="
            + " now() - interval '1 day', result_recorded_at = now(), confirmed_request_id = ?"
            + " WHERE id = ?",
        history.requestId(),
        itemId);
    jdbcTemplate.update(
        "INSERT INTO visit_review (schedule_item_id, request_id, approval_id,"
            + " reviewed_membership_id, reviewed_office_id, approver_link_id, punctuality,"
            + " advance_contact, as_requested, review_deadline_at) VALUES (?, ?, ?, ?, ?, ?,"
            + " 'YES', 'YES', 'SKIP', now() + interval '10 days')",
        itemId,
        history.requestId(),
        s.base().approvalId(),
        s.base().owner().membership().getId(),
        s.base().owner().office().getId(),
        linkId);
  }

  @Test
  void 공동_승인자_링크는_비교와_선택이_모두_읽기_전용으로_403이다() throws Exception {
    SelectionFixture s = selectionFixture();
    long co = registerApproval(s.cookie(), s.base().propertyId(), "CO_APPROVER", -1, 30);
    String coToken = issueApproverToken(s.cookie(), s.base().propertyId(), co);

    assertError(compare(coToken), ErrorCode.APPROVER_READ_ONLY);
    assertError(
        select(coToken, s.requestId(), 1, selectBody(s.requestId())), ErrorCode.APPROVER_READ_ONLY);
    assertThat(requestStatus(s.requestId())).isEqualTo("PENDING");
  }

  @Test
  void 없는_링크와_만료된_링크는_404이고_다른_출입승인의_신청은_선택할_수_없다() throws Exception {
    SelectionFixture s = selectionFixture();
    assertError(compare("not-a-token"), ErrorCode.PUBLIC_LINK_NOT_FOUND);
    jdbcTemplate.update(
        "UPDATE visit_approver_property_link SET expires_at = now() - interval '1 minute'"
            + " WHERE approval_id = ?",
        s.base().approvalId());
    assertError(compare(s.token()), ErrorCode.PUBLIC_LINK_EXPIRED);
    assertError(
        select(s.token(), s.requestId(), 1, selectBody(s.requestId())),
        ErrorCode.PUBLIC_LINK_EXPIRED);

    SelectionFixture other = selectionFixture();
    assertError(
        select(
            other.token(), s.requestId(), requestVersion(s.requestId()), selectBody(s.requestId())),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        select(other.token(), 987654L, 1, selectBody(987654L)), ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 선택하면_재검증을_통과해_확정되고_일정_항목과_기록과_이력이_남는다() throws Exception {
    SelectionFixture s = selectionFixture();

    JsonNode response =
        json(
            select(s.token(), s.requestId(), 1, selectBody(s.requestId()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.requestStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.revalidation.result").value("PASSED"))
                .andExpect(jsonPath("$.revalidation.failureCode").doesNotExist())
                .andExpect(jsonPath("$.timeClosedRequestIds.length()").value(0)));

    long selectionId = response.get("selectionId").longValue();
    Map<String, Object> selection =
        jdbcTemplate.queryForMap(
            "SELECT compared_request_count, approver_link_id, approver_profile_id, selection_note"
                + " FROM visit_request_selection WHERE id = ?",
            selectionId);
    assertThat(selection.get("compared_request_count")).isEqualTo(1);
    assertThat(selection.get("approver_link_id")).isNotNull();
    assertThat(selection.get("approver_profile_id")).isNull();
    Map<String, Object> revalidation =
        jdbcTemplate.queryForMap(
            "SELECT result, failure_code, condition_id FROM visit_request_revalidation WHERE"
                + " selection_id = ?",
            selectionId);
    assertThat(revalidation.get("result")).isEqualTo("PASSED");
    assertThat(revalidation.get("condition_id"))
        .isEqualTo(activeConditionId(s.base().approvalId()));
    Map<String, Object> item =
        jdbcTemplate.queryForMap(
            "SELECT result, confirmed_request_id, travel_starts_at, travel_ends_at,"
                + " candidate_property_id FROM visit_schedule_item WHERE coordination_id = ?",
            s.coordinationId());
    assertThat(item.get("result")).isEqualTo("SCHEDULED");
    assertThat(item.get("confirmed_request_id")).isEqualTo(s.requestId());
    assertThat(item.get("candidate_property_id")).isEqualTo(s.candidateId());
    assertThat(item.get("travel_ends_at")).isNull();
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT to_status FROM visit_request_status_history WHERE request_id = ? ORDER BY"
                    + " id",
                String.class,
                s.requestId()))
        .containsExactly("PENDING", "SELECTED", "CONFIRMED");
    assertThat(coordinationStatus(s.coordinationId())).isEqualTo("CONFIRMED");
    assertThat(
            count(
                "SELECT count(*) FROM visit_coordination_status_history WHERE coordination_id = ?"
                    + " AND from_status = 'APPLYING' AND to_status = 'CONFIRMED' AND source ="
                    + " 'APPROVER'",
                s.coordinationId()))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT notice_type FROM visit_request_notice WHERE request_id = ? ORDER BY id",
                String.class,
                s.requestId()))
        .containsExactly("SELECTED", "CONFIRMED");
    assertThat(
            count(
                "SELECT count(*) FROM visit_selection_trust_snapshot WHERE selection_id = ? AND"
                    + " is_selected AND policy_version = 'TRUST_V1'",
                selectionId))
        .isEqualTo(1);
    assertThat(requestVersion(s.requestId())).isGreaterThan(1);
  }

  @Test
  void 확정되면_겹치거나_최소_간격을_못_지키는_다른_신청은_시간_마감되고_충분한_간격은_남는다() throws Exception {
    SelectionFixture s = selectionFixture();
    var agentCookie = cookieOf(staffIn(s.base().owner().office(), OfficeMembershipRole.AGENT));
    SecondRequest overlapping = secondRequest(s.base(), agentCookie, "10:15", "10:45", 9, 17);
    SecondRequest tooClose = secondRequest(s.base(), agentCookie, "10:40", "11:10", 9, 17);
    SecondRequest enough = secondRequest(s.base(), agentCookie, "11:00", "11:30", 9, 17);

    JsonNode response =
        json(
            selectNow(s.token(), s.requestId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("CONFIRMED")));

    List<Long> closed = new ArrayList<>();
    response.get("timeClosedRequestIds").forEach(id -> closed.add(id.longValue()));
    assertThat(closed).containsExactlyInAnyOrder(overlapping.requestId(), tooClose.requestId());
    assertThat(requestStatus(overlapping.requestId())).isEqualTo("TIME_CLOSED");
    assertThat(requestStatus(tooClose.requestId())).isEqualTo("TIME_CLOSED");
    assertThat(requestStatus(enough.requestId())).isEqualTo("PENDING");
    assertThat(coordinationStatus(overlapping.coordinationId()))
        .isEqualTo("FINAL_CONFIRMATION_REQUIRED");
    assertThat(coordinationStatus(enough.coordinationId())).isEqualTo("APPLYING");
    assertThat(
            count(
                "SELECT count(*) FROM visit_request_notice WHERE request_id = ? AND notice_type ="
                    + " 'TIME_CLOSED'",
                overlapping.requestId()))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM visit_request_status_history WHERE request_id = ? AND"
                    + " to_status = 'TIME_CLOSED' AND source = 'APPROVER'",
                overlapping.requestId()))
        .isEqualTo(1);
  }

  @Test
  void 신청이_대기가_아니면_409이고_다시_선택해도_재검증_기록이_늘지_않는다() throws Exception {
    SelectionFixture s = selectionFixture();
    mockMvc
        .perform(jsonPostAs("/api/visit-requests/" + s.requestId() + "/withdraw", "{}", s.cookie()))
        .andExpect(status().isOk());

    assertError(
        select(s.token(), s.requestId(), requestVersion(s.requestId()), selectBody(s.requestId())),
        ErrorCode.VISIT_REQUEST_INVALID_STATE);
    assertThat(
            count(
                "SELECT count(*) FROM visit_request_selection WHERE request_id = ?", s.requestId()))
        .isZero();
  }

  @Test
  void 희망_시작이_지난_신청은_409로_거절하고_종료_처리하며_재검증_기록은_만들지_않는다() throws Exception {
    SelectionFixture s = selectionFixture();
    jdbcTemplate.update(
        "UPDATE visit_request SET requested_start_at = now() - interval '2 hours',"
            + " requested_end_at = now() - interval '90 minutes' WHERE id = ?",
        s.requestId());

    assertError(
        select(s.token(), s.requestId(), requestVersion(s.requestId()), selectBody(s.requestId())),
        ErrorCode.VISIT_REQUEST_INVALID_STATE);

    assertThat(requestStatus(s.requestId())).isEqualTo("EXPIRED");
    assertThat(
            count(
                "SELECT count(*) FROM visit_request_revalidation WHERE request_id = ?",
                s.requestId()))
        .isZero();
    assertThat(coordinationStatus(s.coordinationId())).isEqualTo("FINAL_CONFIRMATION_REQUIRED");
  }

  @Test
  void If_Match가_없으면_400이고_어긋나면_409이며_비교_목록과_안내_버전을_검사한다() throws Exception {
    SelectionFixture s = selectionFixture();

    mockMvc
        .perform(
            AuthFlowSupport.withCsrf(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        compareUrl(s.token()) + "/" + s.requestId() + "/select")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content(selectBody(s.requestId()))))
        .andExpect(status().isBadRequest());
    assertError(
        select(s.token(), s.requestId(), 7, selectBody(s.requestId())), ErrorCode.VERSION_CONFLICT);
    assertError(
        select(s.token(), s.requestId(), 1, selectBody("2099-01", s.requestId())),
        ErrorCode.NOTICE_VERSION_CHANGED);
    select(
            s.token(),
            s.requestId(),
            1,
            "{\"comparedRequestIds\":[],\"acknowledgedNoticeVersion\":\"1.0\"}")
        .andExpect(status().isBadRequest());
    select(s.token(), s.requestId(), 1, selectBody(s.requestId() + 1))
        .andExpect(status().isBadRequest());
    select(s.token(), s.requestId(), 1, "{\"acknowledgedNoticeVersion\":\"1.0\",\"x\":1}")
        .andExpect(status().isBadRequest());

    assertThat(requestStatus(s.requestId())).isEqualTo("PENDING");
    assertThat(
            count(
                "SELECT count(*) FROM visit_request_selection WHERE request_id = ?", s.requestId()))
        .isZero();
  }

  @Test
  void 선택_요청이_출입승인당_분당_10회를_넘으면_429다() throws Exception {
    SelectionFixture s = selectionFixture();
    for (int i = 0; i < 10; i++) {
      select(s.token(), s.requestId(), 1, "{}").andExpect(status().isBadRequest());
    }
    assertError(select(s.token(), s.requestId(), 1, "{}"), ErrorCode.RATE_LIMITED);
  }

  @Test
  void 신청_뒤_조건이_바뀌어_위반하면_조건_변경으로_재검증_실패하고_종결된다() throws Exception {
    SelectionFixture s = selectionFixture();
    changeCondition(s.base(), Map.of("windows", windows("14:00", "18:00")));

    JsonNode response =
        json(
            selectNow(s.token(), s.requestId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("REVALIDATION_FAILED"))
                .andExpect(jsonPath("$.revalidation.result").value("FAILED"))
                .andExpect(jsonPath("$.revalidation.failureCode").value("CONDITION_CHANGED"))
                .andExpect(jsonPath("$.timeClosedRequestIds.length()").value(0)));

    assertThat(response.get("revalidation").get("failureDetail").asString())
        .contains("WINDOW_MISMATCH");
    assertThat(requestStatus(s.requestId())).isEqualTo("REVALIDATION_FAILED");
    assertThat(scheduleItemCount(s.coordinationId())).isZero();
    assertThat(coordinationStatus(s.coordinationId())).isEqualTo("FINAL_CONFIRMATION_REQUIRED");
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT notice_type FROM visit_request_notice WHERE request_id = ? ORDER BY id",
                String.class,
                s.requestId()))
        .containsExactly("SELECTED", "REVALIDATION_FAILED");
    Map<String, Object> failed =
        jdbcTemplate.queryForMap(
            "SELECT result, failure_code FROM visit_request_revalidation WHERE request_id = ?",
            s.requestId());
    assertThat(failed.get("result")).isEqualTo("FAILED");
    assertThat(failed.get("failure_code")).isEqualTo("CONDITION_CHANGED");
  }

  @Test
  void 조건이_바뀌지_않았는데_위반하면_위반한_규칙_코드를_그대로_쓴다() throws Exception {
    SelectionFixture s = selectionFixture();
    jdbcTemplate.update(
        "UPDATE visit_approver_condition SET applies_until = now() + interval '1 day' WHERE"
            + " approval_id = ?",
        s.base().approvalId());

    selectNow(s.token(), s.requestId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestStatus").value("REVALIDATION_FAILED"))
        .andExpect(jsonPath("$.revalidation.failureCode").value("CONDITION_EXPIRED"));
  }

  @Test
  void 출입승인이_철회되면_권한_없음으로_재검증_실패한다() throws Exception {
    SelectionFixture s = selectionFixture();
    jdbcTemplate.update(
        "UPDATE property_access_approval SET revoked_at = now(), revocation_reason = '철회',"
            + " revocation_recorded_at = now(), revoked_by_user_id = ? WHERE id = ?",
        s.base().owner().user().getId(),
        s.base().approvalId());

    selectNow(s.token(), s.requestId())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestStatus").value("REVALIDATION_FAILED"))
        .andExpect(jsonPath("$.revalidation.failureCode").value("NO_PERMISSION"));
  }

  @Test
  void 고객_가능_시간이_바뀌어_밖이_되면_고객_일정_충돌로_실패한다() throws Exception {
    SelectionFixture s = selectionFixture();
    jdbcTemplate.update(
        "UPDATE visit_customer_time_slot SET starts_at = starts_at + interval '5 hours', ends_at"
            + " = ends_at + interval '5 hours' WHERE customer_link_id IN (SELECT id FROM"
            + " visit_customer_link WHERE coordination_id = ?)",
        s.coordinationId());

    selectNow(s.token(), s.requestId())
        .andExpect(jsonPath("$.requestStatus").value("REVALIDATION_FAILED"))
        .andExpect(jsonPath("$.revalidation.failureCode").value("CUSTOMER_CONFLICT"));
  }

  @Test
  void 담당_중개사의_다른_확정_방문과_겹치면_중개사_일정_충돌로_실패한다() throws Exception {
    SelectionFixture s = selectionFixture();
    long otherProperty = newProperty(s.cookie());
    long otherCustomer =
        insertCustomer(
            s.base().owner().office().getId(), s.base().owner().membership().getId(), "박다른");
    long otherCoordination =
        createCoordination(
                s.cookie(), otherCustomer, null, today(), today().plusDays(10), otherProperty)
            .get("coordinationId")
            .longValue();
    long otherCandidate =
        jdbcTemplate.queryForObject(
            "SELECT id FROM visit_candidate_property WHERE coordination_id = ?",
            Long.class,
            otherCoordination);
    insertBusyItem(
        otherCoordination,
        otherCandidate,
        s.base().owner().office().getId(),
        today().plusDays(3),
        "10:10",
        "10:40",
        null);

    selectNow(s.token(), s.requestId())
        .andExpect(jsonPath("$.requestStatus").value("REVALIDATION_FAILED"))
        .andExpect(jsonPath("$.revalidation.failureCode").value("BROKER_CONFLICT"));
  }

  @Test
  void 담당_중개사의_앞_방문_이동_구간과_겹치면_이동_시간_충돌로_실패한다() throws Exception {
    SelectionFixture s = selectionFixture();
    long otherProperty = newProperty(s.cookie());
    long otherCustomer =
        insertCustomer(
            s.base().owner().office().getId(), s.base().owner().membership().getId(), "박다른");
    long otherCoordination =
        createCoordination(
                s.cookie(), otherCustomer, null, today(), today().plusDays(10), otherProperty)
            .get("coordinationId")
            .longValue();
    long otherCandidate =
        jdbcTemplate.queryForObject(
            "SELECT id FROM visit_candidate_property WHERE coordination_id = ?",
            Long.class,
            otherCoordination);
    insertBusyItem(
        otherCoordination,
        otherCandidate,
        s.base().owner().office().getId(),
        today().plusDays(3),
        "09:20",
        "09:50",
        "10:10");

    selectNow(s.token(), s.requestId())
        .andExpect(jsonPath("$.requestStatus").value("REVALIDATION_FAILED"))
        .andExpect(jsonPath("$.revalidation.failureCode").value("TRAVEL_CONFLICT"));
  }

  @Test
  void 같은_매물의_다른_확정_방문과_겹치면_매물_일정_충돌로_실패한다() throws Exception {
    SelectionFixture s = selectionFixture();
    changeCondition(s.base(), Map.of("minGapMinutes", "0"));
    long otherCustomer =
        insertCustomer(
            s.base().owner().office().getId(), s.base().owner().membership().getId(), "박다른");
    var agent = staffIn(s.base().owner().office(), OfficeMembershipRole.AGENT);
    long otherCoordination =
        createCoordination(
                cookieOf(agent),
                otherCustomer,
                agent.membership().getId(),
                today(),
                today().plusDays(10),
                s.base().propertyId())
            .get("coordinationId")
            .longValue();
    long otherCandidate =
        jdbcTemplate.queryForObject(
            "SELECT id FROM visit_candidate_property WHERE coordination_id = ?",
            Long.class,
            otherCoordination);
    insertBusyItem(
        otherCoordination,
        otherCandidate,
        s.base().owner().office().getId(),
        today().plusDays(3),
        "10:00",
        "10:30",
        null);

    selectNow(s.token(), s.requestId())
        .andExpect(jsonPath("$.requestStatus").value("REVALIDATION_FAILED"))
        .andExpect(jsonPath("$.revalidation.failureCode").value("PROPERTY_CONFLICT"));
  }

  @Test
  void 같은_멱등_키로_다시_보내면_처음_결과를_돌려주고_새로_처리하지_않는다() throws Exception {
    SelectionFixture s = selectionFixture();
    String body = selectBody(s.requestId());

    JsonNode first =
        json(select(s.token(), s.requestId(), 1, body, "key-1").andExpect(status().isOk()));
    JsonNode again =
        json(select(s.token(), s.requestId(), 1, body, "key-1").andExpect(status().isOk()));

    assertThat(again.get("selectionId").longValue())
        .isEqualTo(first.get("selectionId").longValue());
    assertThat(again.get("requestStatus").asString()).isEqualTo("CONFIRMED");
    assertThat(
            count(
                "SELECT count(*) FROM visit_request_selection WHERE request_id = ?", s.requestId()))
        .isEqualTo(1);
    assertThat(scheduleItemCount(s.coordinationId())).isEqualTo(1);
  }

  @Test
  void 겹치는_신청을_동시에_선택하면_한_건만_확정되고_나머지는_마감된다() throws Exception {
    SelectionFixture s = selectionFixture();
    var agentCookie = cookieOf(staffIn(s.base().owner().office(), OfficeMembershipRole.AGENT));
    SecondRequest second = secondRequest(s.base(), agentCookie, "10:10", "10:40", 9, 17);
    long firstVersion = requestVersion(s.requestId());
    long secondVersion = requestVersion(second.requestId());

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Integer>> tasks = new ArrayList<>();
    tasks.add(
        () -> {
          start.await();
          return select(s.token(), s.requestId(), firstVersion, selectBody(s.requestId()))
              .andReturn()
              .getResponse()
              .getStatus();
        });
    tasks.add(
        () -> {
          start.await();
          return select(
                  s.token(), second.requestId(), secondVersion, selectBody(second.requestId()))
              .andReturn()
              .getResponse()
              .getStatus();
        });
    List<Future<Integer>> futures = new ArrayList<>();
    for (Callable<Integer> task : tasks) {
      futures.add(pool.submit(task));
    }
    start.countDown();
    List<Integer> codes = new ArrayList<>();
    for (Future<Integer> future : futures) {
      codes.add(future.get());
    }
    pool.shutdown();

    assertThat(codes).containsExactlyInAnyOrder(200, 409);
    List<String> statuses =
        List.of(requestStatus(s.requestId()), requestStatus(second.requestId()));
    assertThat(statuses).containsExactlyInAnyOrder("CONFIRMED", "TIME_CLOSED");
    assertThat(
            count(
                "SELECT count(*) FROM visit_schedule_item WHERE result = 'SCHEDULED' AND"
                    + " candidate_property_id IN (?, ?)",
                s.candidateId(),
                second.candidateId()))
        .isEqualTo(1);
  }

  @Test
  void 겹치지_않는_신청을_동시에_선택하면_둘_다_확정되고_교착하지_않는다() throws Exception {
    SelectionFixture s = selectionFixture();
    var agentCookie = cookieOf(staffIn(s.base().owner().office(), OfficeMembershipRole.AGENT));
    SecondRequest second = secondRequest(s.base(), agentCookie, "13:00", "13:30", 9, 17);
    long firstVersion = requestVersion(s.requestId());
    long secondVersion = requestVersion(second.requestId());

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    Future<Integer> a =
        pool.submit(
            () -> {
              start.await();
              return select(s.token(), s.requestId(), firstVersion, selectBody(s.requestId()))
                  .andReturn()
                  .getResponse()
                  .getStatus();
            });
    Future<Integer> b =
        pool.submit(
            () -> {
              start.await();
              return select(
                      s.token(), second.requestId(), secondVersion, selectBody(second.requestId()))
                  .andReturn()
                  .getResponse()
                  .getStatus();
            });
    start.countDown();
    assertThat(a.get()).isEqualTo(200);
    assertThat(b.get()).isEqualTo(200);
    pool.shutdown();

    assertThat(requestStatus(s.requestId())).isEqualTo("CONFIRMED");
    assertThat(requestStatus(second.requestId())).isEqualTo("CONFIRMED");
  }

  @Test
  void 모든_후보가_확정되지_않으면_조율은_신청_진행에_머물고_종결되면_최종_확정_필요로_돌아간다() throws Exception {
    SelectionFixture s = selectionFixture();
    ExtraCandidate extra = addCandidateWithApproval(s.base());
    long extraRequest =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPostAs(
                            requestUrl(s.coordinationId()),
                            requestBody(extra.candidateId(), today().plusDays(3), "11:30", "12:00"),
                            s.cookie()))
                    .andExpect(status().isCreated())
                    .andReturn())
            .get("requestId")
            .longValue();

    selectNow(s.token(), s.requestId()).andExpect(jsonPath("$.requestStatus").value("CONFIRMED"));
    assertThat(coordinationStatus(s.coordinationId())).isEqualTo("APPLYING");
    assertThat(requestStatus(extraRequest)).isEqualTo("PENDING");

    mockMvc
        .perform(jsonPostAs("/api/visit-requests/" + extraRequest + "/withdraw", "{}", s.cookie()))
        .andExpect(status().isOk());
    assertThat(coordinationStatus(s.coordinationId())).isEqualTo("FINAL_CONFIRMATION_REQUIRED");
    assertThat(scheduleItemCount(s.coordinationId())).isEqualTo(1);
  }

  @Test
  void 실제_확정_방문이_있으면_조건_변경_영향이_판정되고_확인_후_재조율_필요가_된다() throws Exception {
    SelectionFixture s = selectionFixture();
    selectNow(s.token(), s.requestId()).andExpect(jsonPath("$.requestStatus").value("CONFIRMED"));
    long itemId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM visit_schedule_item WHERE coordination_id = ?",
            Long.class,
            s.coordinationId());
    String narrowed = conditionJson(false, Map.of("windows", windows("14:00", "18:00")));

    mockMvc
        .perform(
            AuthFlowSupport.withCsrf(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        conditionsUrl(s.token()) + "/impact-preview")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content(narrowed)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasImpact").value(true))
        .andExpect(jsonPath("$.affectedItems[0].scheduleItemId").value(itemId))
        .andExpect(jsonPath("$.affectedItems[0].violatedRules[0]").value("WINDOW_MISMATCH"));

    assertError(
        mockMvc.perform(jsonPutAs(conditionsUrl(s.token()), narrowed, null, null)),
        ErrorCode.IMPACT_NOT_ACKNOWLEDGED);
    assertThat(coordinationStatus(s.coordinationId())).isEqualTo("CONFIRMED");

    mockMvc
        .perform(
            jsonPutAs(
                conditionsUrl(s.token()),
                conditionJson(
                    false,
                    Map.of("windows", windows("14:00", "18:00"), "impactAcknowledged", "true")),
                null,
                null))
        .andExpect(status().isOk());

    assertThat(coordinationStatus(s.coordinationId())).isEqualTo("RESCHEDULE_REQUIRED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT result FROM visit_schedule_item WHERE id = ?", String.class, itemId))
        .isEqualTo("SCHEDULED");
    assertThat(
            count(
                "SELECT count(*) FROM visit_request_notice WHERE request_id = ? AND notice_type ="
                    + " 'CONDITION_CHANGE_IMPACT'",
                s.requestId()))
        .isEqualTo(1);
  }

  @Test
  void 출입승인이_없는_확정_경로는_없다() throws Exception {
    SelectionFixture s = selectionFixture();
    Cookie cookie = s.cookie();

    mockMvc
        .perform(
            jsonPostAs("/api/visit-coordinations/" + s.coordinationId() + "/confirm", "{}", cookie))
        .andExpect(status().is4xxClientError());
    mockMvc
        .perform(get("/api/visit-coordinations/" + s.coordinationId()).cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scheduleItems.length()").value(0));
    assertThat(scheduleItemCount(s.coordinationId())).isZero();
    assertThat(coordinationStatus(s.coordinationId())).isEqualTo("APPLYING");
  }
}
