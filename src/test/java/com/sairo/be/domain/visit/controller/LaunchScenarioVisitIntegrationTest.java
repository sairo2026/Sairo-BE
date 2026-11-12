package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.visit.service.VisitRequestExpiryService;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.ApproverAccountTestBase;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * 개발플로우 4장 필수 시나리오 ④ ⑤ ⑬ ⑭를 실제 엔드포인트 순서로 잇는 출시 검증 시험이다. 조율 상태는 SQL로 바꾸지 않고 고객 시간 제출, 일정 구성, 신청, 승인자
 * 선택으로만 옮긴다.
 */
@IntegrationTest
class LaunchScenarioVisitIntegrationTest extends ApproverAccountTestBase {

  @Autowired private VisitRequestExpiryService expiryService;

  private record Desk(TestData.Staff staff, Cookie cookie) {

    long officeId() {
      return staff.office().getId();
    }

    long membershipId() {
      return staff.membership().getId();
    }
  }

  private record Approval(long propertyId, long approvalId, String token) {}

  private Desk owner() {
    TestData.Staff staff = newStaff(OfficeMembershipRole.OWNER);
    return new Desk(staff, cookieOf(staff));
  }

  private Desk colleague(Desk owner, OfficeMembershipRole role) {
    TestData.Staff staff = staffIn(owner.staff().office(), role);
    return new Desk(staff, cookieOf(staff));
  }

  private long customer(Desk desk, String name) throws Exception {
    return createCustomer(desk.cookie(), name, null, desk.membershipId());
  }

  private Approval approvalWithCondition(Desk desk, long propertyId, Map<String, String> overrides)
      throws Exception {
    long approvalId = registerApproval(desk.cookie(), propertyId, null, -1, 30);
    String token = issueApproverToken(desk.cookie(), propertyId, approvalId);
    mockMvc
        .perform(jsonPutAs(conditionsUrl(token), conditionJson(false, overrides), null, null))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", containsString("no-store")))
        .andExpect(jsonPath("$.source").value("DIRECT"));
    return new Approval(propertyId, approvalId, token);
  }

  private Approval approvalWithCondition(Desk desk, long propertyId) throws Exception {
    return approvalWithCondition(desk, propertyId, Map.of());
  }

  private Approval proxyApproval(Desk desk, long propertyId) throws Exception {
    long approvalId = registerApproval(desk.cookie(), propertyId, null, -1, 30);
    mockMvc
        .perform(
            jsonPutAs(
                approvalsUrl(propertyId) + "/" + approvalId + "/approver-conditions/proxy",
                conditionJson(true),
                desk.cookie(),
                null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("PROXY"));
    return new Approval(
        propertyId, approvalId, issueApproverToken(desk.cookie(), propertyId, approvalId));
  }

  private long coordination(Desk desk, long customerId, Long... propertyIds) throws Exception {
    JsonNode created =
        createCoordination(
            desk.cookie(), customerId, null, today(), today().plusDays(10), propertyIds);
    assertThat(created.get("status").asString()).isEqualTo("TIME_COLLECTING");
    return created.get("coordinationId").longValue();
  }

  private long candidateOf(long coordinationId, long propertyId) {
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT id FROM visit_candidate_property WHERE coordination_id = ? AND property_id = ?",
            Long.class,
            coordinationId,
            propertyId);
    return id == null ? -1 : id;
  }

  private static String slot(LocalDate day, String from, String to) {
    return "{\"startsAt\":\""
        + day
        + "T"
        + from
        + ":00+09:00\",\"endsAt\":\""
        + day
        + "T"
        + to
        + ":00+09:00\"}";
  }

  private String customerTimes(Desk desk, long coordinationId, String... slots) throws Exception {
    String token = issueToken(desk.cookie(), coordinationId);
    mockMvc
        .perform(
            jsonPostAs(
                "/api/public/customer-visit-links/" + token + "/available-times",
                "{\"timeSlots\":["
                    + String.join(",", slots)
                    + "],\"acknowledgedNoticeVersion\":\""
                    + NOTICE_VERSION
                    + "\"}",
                null))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", containsString("no-store")));
    assertThat(coordinationStatus(coordinationId)).isEqualTo("COORDINATABLE");
    return token;
  }

  private void compose(Desk desk, long coordinationId, long... candidateIds) throws Exception {
    String items =
        LongStream.range(0, candidateIds.length)
            .mapToObj(
                index ->
                    "{\"candidatePropertyId\":"
                        + candidateIds[(int) index]
                        + ",\"sortOrder\":"
                        + (index + 1)
                        + ",\"visitDurationMinutes\":30,\"travelDurationMinutes\":0}")
            .collect(Collectors.joining(",", "{\"items\":[", "]}"));
    mockMvc
        .perform(
            jsonPutAs(
                "/api/visit-coordinations/" + coordinationId + "/schedule",
                items,
                desk.cookie(),
                coordinationVersion(coordinationId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FINAL_CONFIRMATION_REQUIRED"));
  }

  private ResultActions submitRaw(
      Desk desk, long coordinationId, long candidateId, LocalDate day, String from, String to)
      throws Exception {
    return mockMvc.perform(
        jsonPostAs(
            requestUrl(coordinationId), requestBody(candidateId, day, from, to), desk.cookie()));
  }

  private long submitRequest(
      Desk desk, long coordinationId, long candidateId, LocalDate day, String from, String to)
      throws Exception {
    return AuthFlowSupport.json(
            submitRaw(desk, coordinationId, candidateId, day, from, to)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn())
        .get("requestId")
        .longValue();
  }

  private List<String> transitions(long coordinationId) {
    return jdbcTemplate.queryForList(
        "SELECT to_status FROM visit_coordination_status_history WHERE coordination_id = ? ORDER"
            + " BY id",
        String.class,
        coordinationId);
  }

  private List<String> requestTransitions(long requestId) {
    return jdbcTemplate.queryForList(
        "SELECT to_status FROM visit_request_status_history WHERE request_id = ? ORDER BY id",
        String.class,
        requestId);
  }

  private long rowsContaining(String table, String text) {
    return count(
        "SELECT count(*) FROM " + table + " t WHERE row_to_json(t)::text LIKE ?", "%" + text + "%");
  }

  @Test
  void 시나리오4_조율_생성부터_출입승인_3건_시간_수집_일정_구성_신청_선택_확정_결과_기록까지_잇는다() throws Exception {
    Desk desk = owner();
    long customerId = customer(desk, "이시나");
    long first = newProperty(desk.cookie());
    long second = newProperty(desk.cookie());
    long third = newProperty(desk.cookie());

    long coordinationId = coordination(desk, customerId, first, second, third);
    List<Approval> approvals =
        List.of(
            approvalWithCondition(desk, first),
            approvalWithCondition(desk, second),
            approvalWithCondition(desk, third));
    LocalDate day = visitDay();
    String customerToken = customerTimes(desk, coordinationId, slot(day, "10:00", "16:00"));
    assertThat(rowsContaining("visit_customer_link", customerToken)).isZero();
    for (Approval approval : approvals) {
      assertThat(rowsContaining("visit_approver_property_link", approval.token())).isZero();
    }

    long[] candidates = {
      candidateOf(coordinationId, first),
      candidateOf(coordinationId, second),
      candidateOf(coordinationId, third)
    };
    compose(desk, coordinationId, candidates);

    long[] requests = {
      submitRequest(desk, coordinationId, candidates[0], day, "10:00", "10:30"),
      submitRequest(desk, coordinationId, candidates[1], day, "11:00", "11:30"),
      submitRequest(desk, coordinationId, candidates[2], day, "12:00", "12:30")
    };
    assertThat(coordinationStatus(coordinationId)).isEqualTo("APPLYING");

    selectNow(approvals.get(0).token(), requests[0])
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", containsString("no-store")))
        .andExpect(jsonPath("$.requestStatus").value("CONFIRMED"))
        .andExpect(jsonPath("$.revalidation.result").value("PASSED"));
    selectNow(approvals.get(1).token(), requests[1])
        .andExpect(jsonPath("$.requestStatus").value("CONFIRMED"));
    assertThat(coordinationStatus(coordinationId)).isEqualTo("APPLYING");
    selectNow(approvals.get(2).token(), requests[2])
        .andExpect(jsonPath("$.requestStatus").value("CONFIRMED"));
    assertThat(coordinationStatus(coordinationId)).isEqualTo("CONFIRMED");
    for (long requestId : requests) {
      assertThat(requestTransitions(requestId)).containsExactly("PENDING", "SELECTED", "CONFIRMED");
    }
    assertThat(
            count(
                "SELECT count(*) FROM visit_schedule_item WHERE coordination_id = ? AND result ="
                    + " 'SCHEDULED' AND confirmed_request_id IS NOT NULL",
                coordinationId))
        .isEqualTo(3);

    mockMvc
        .perform(authedGet(coordinationUrl(coordinationId), desk.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scheduleItems.length()").value(3));
    assertError(
        mockMvc.perform(authedGet(coordinationUrl(coordinationId), owner().cookie())),
        ErrorCode.RESOURCE_NOT_FOUND);

    for (long candidateId : candidates) {
      long itemId = itemOfCandidate(coordinationId, candidateId);
      patchResult(desk.cookie(), itemId, completedBody(30), coordinationVersion(coordinationId))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.result").value("COMPLETED"));
    }
    assertThat(coordinationStatus(coordinationId)).isEqualTo("VISIT_COMPLETED");
    assertThat(
            count(
                "SELECT count(*) FROM visit_schedule_item_result_history h JOIN"
                    + " visit_schedule_item i ON i.id = h.schedule_item_id WHERE i.coordination_id"
                    + " = ? AND h.result = 'COMPLETED'",
                coordinationId))
        .isEqualTo(3);
    assertThat(transitions(coordinationId))
        .containsExactly(
            "TIME_COLLECTING",
            "COORDINATABLE",
            "FINAL_CONFIRMATION_REQUIRED",
            "APPLYING",
            "CONFIRMED",
            "VISIT_COMPLETED");
  }

  @Test
  void 시나리오5_확정된_조율에서_점유자가_바뀌면_철회_접수로_재조율_필요가_되고_새_승인자가_변경_신청을_선택해_재확정된다() throws Exception {
    Desk desk = owner();
    long customerId = customer(desk, "박재조");
    long propertyId = newProperty(desk.cookie());
    long coordinationId = coordination(desk, customerId, propertyId);
    Approval previous = approvalWithCondition(desk, propertyId);
    LocalDate day = visitDay();
    customerTimes(desk, coordinationId, slot(day, "10:00", "16:00"));
    long candidateId = candidateOf(coordinationId, propertyId);
    compose(desk, coordinationId, candidateId);
    long requestId = submitRequest(desk, coordinationId, candidateId, day, "10:00", "10:30");
    selectNow(previous.token(), requestId)
        .andExpect(jsonPath("$.requestStatus").value("CONFIRMED"));
    long itemId = scheduledItem(coordinationId);

    postVersioned(
            coordinationUrl(coordinationId) + "/access-revocations",
            "{\"approvalId\":"
                + previous.approvalId()
                + ",\"revokedAt\":\""
                + minutesAgo(5)
                + "\",\"revocationReason\":\"점유자 변경\"}",
            desk.cookie(),
            coordinationVersion(coordinationId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RESCHEDULE_REQUIRED"));
    assertThat(coordinationStatus(coordinationId)).isEqualTo("RESCHEDULE_REQUIRED");
    assertThat(itemResult(itemId)).isEqualTo("SCHEDULED");

    long newApprovalId =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPostAs(
                            approvalsUrl(propertyId),
                            "{\"approverType\":\"OCCUPANT\",\"approverName\":\"최새점\","
                                + "\"approverPhone\":\"010-3333-4444\",\"validFrom\":\""
                                + today().minusDays(1)
                                + "T09:00:00+09:00\",\"validUntil\":\""
                                + today().plusDays(30)
                                + "T18:00:00+09:00\",\"source\":\"DIRECT\"}",
                            desk.cookie()))
                    .andExpect(status().isCreated())
                    .andReturn())
            .get("approvalId")
            .longValue();
    String newToken = issueApproverToken(desk.cookie(), propertyId, newApprovalId);
    mockMvc
        .perform(jsonPutAs(conditionsUrl(newToken), conditionJson(false), null, null))
        .andExpect(status().isOk());

    long changeId =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPostAs(
                            itemUrl(itemId) + "/change-requests",
                            "{\"requestedStartAt\":\""
                                + day
                                + "T13:00:00+09:00\",\"requestedEndAt\":\""
                                + day
                                + "T13:30:00+09:00\",\"expectedDurationMinutes\":30}",
                            desk.cookie()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.requestKind").value("CHANGE"))
                    .andReturn())
            .get("requestId")
            .longValue();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT approval_id FROM visit_request WHERE id = ?", Long.class, changeId))
        .isEqualTo(newApprovalId);
    assertThat(coordinationStatus(coordinationId)).isEqualTo("RESCHEDULE_REQUIRED");

    selectNow(newToken, changeId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestStatus").value("CONFIRMED"))
        .andExpect(jsonPath("$.revalidation.result").value("PASSED"));

    assertThat(coordinationStatus(coordinationId)).isEqualTo("CONFIRMED");
    assertThat(itemResult(itemId)).isEqualTo("CANCELLED");
    assertThat(requestStatus(requestId)).isEqualTo("CANCELLED");
    long replacement = scheduledItem(coordinationId);
    assertThat(replacement).isNotEqualTo(itemId);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT confirmed_request_id FROM visit_schedule_item WHERE id = ?",
                Long.class,
                replacement))
        .isEqualTo(changeId);
    assertThat(transitions(coordinationId))
        .containsSubsequence("CONFIRMED", "RESCHEDULE_REQUIRED", "CONFIRMED");
  }

  @Test
  void 시나리오13_같은_매물에_두_중개사의_신청이_공존하고_선택_재검증_확정_시간_마감_조건_위반_422_빈자리_비승격을_지킨다() throws Exception {
    Desk owner = owner();
    Desk agent = colleague(owner, OfficeMembershipRole.AGENT);
    long propertyId = newProperty(owner.cookie());
    Approval approval =
        approvalWithCondition(
            owner,
            propertyId,
            Map.of(
                "maxVisitsPerDay", "2",
                "minGapMinutes", "30",
                "minLeadMinutes", "60",
                "singleVisitMaxMinutes", "60"));
    mockMvc
        .perform(authedGet(conditionsUrl(approval.token()), null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.maxVisitsPerDay").value(2))
        .andExpect(jsonPath("$.minGapMinutes").value(30))
        .andExpect(jsonPath("$.minLeadMinutes").value(60))
        .andExpect(jsonPath("$.singleVisitMaxMinutes").value(60))
        .andExpect(jsonPath("$.windows.length()").value(7));

    LocalDate day = visitDay();
    long ownerCoordination = coordination(owner, customer(owner, "김소유"), propertyId);
    long agentCoordination = coordination(agent, customer(agent, "정소속"), propertyId);
    customerTimes(owner, ownerCoordination, slot(day, "10:00", "16:00"));
    customerTimes(agent, agentCoordination, slot(day, "10:00", "16:00"));
    long ownerCandidate = candidateOf(ownerCoordination, propertyId);
    long agentCandidate = candidateOf(agentCoordination, propertyId);
    compose(owner, ownerCoordination, ownerCandidate);
    compose(agent, agentCoordination, agentCandidate);

    long ownerRequest =
        submitRequest(owner, ownerCoordination, ownerCandidate, day, "10:00", "10:30");
    mockMvc
        .perform(
            jsonPostAs(
                requestUrl(agentCoordination),
                requestBody(agentCandidate, day, "13:00", "14:30", "90", ""),
                agent.cookie()))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("APPROVER_CONDITION_VIOLATED"))
        .andExpect(jsonPath("$.violatedRules", hasItem("SINGLE_VISIT_MAX")));
    assertThat(
            count(
                "SELECT count(*) FROM visit_request WHERE coordination_id = ?", agentCoordination))
        .isZero();
    long agentRequest =
        submitRequest(agent, agentCoordination, agentCandidate, day, "10:15", "10:45");
    assertThat(coordinationStatus(ownerCoordination)).isEqualTo("APPLYING");
    assertThat(coordinationStatus(agentCoordination)).isEqualTo("APPLYING");

    JsonNode compared =
        AuthFlowSupport.json(
            compare(approval.token())
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andReturn());
    assertThat(compared.toString()).doesNotContain("김소유").doesNotContain("정소속");
    assertThat(compared.toString()).contains(String.valueOf(ownerRequest));
    assertThat(compared.toString()).contains(String.valueOf(agentRequest));
    assertError(
        mockMvc.perform(authedGet("/api/visit-requests/" + agentRequest, owner().cookie())),
        ErrorCode.RESOURCE_NOT_FOUND);

    selectNow(approval.token(), ownerRequest)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestStatus").value("CONFIRMED"))
        .andExpect(jsonPath("$.revalidation.result").value("PASSED"))
        .andExpect(jsonPath("$.timeClosedRequestIds[0]").value(agentRequest));
    assertThat(requestStatus(agentRequest)).isEqualTo("TIME_CLOSED");
    assertThat(requestTransitions(agentRequest)).containsExactly("PENDING", "TIME_CLOSED");
    assertThat(
            count(
                "SELECT count(*) FROM visit_request_notice WHERE request_id = ? AND notice_type ="
                    + " 'TIME_CLOSED'",
                agentRequest))
        .isEqualTo(1);
    assertThat(coordinationStatus(ownerCoordination)).isEqualTo("CONFIRMED");
    assertThat(coordinationStatus(agentCoordination)).isEqualTo("FINAL_CONFIRMATION_REQUIRED");
    assertThat(
            count(
                "SELECT count(*) FROM visit_request_revalidation WHERE request_id = ? AND result ="
                    + " 'PASSED'",
                ownerRequest))
        .isEqualTo(1);

    long itemId = scheduledItem(ownerCoordination);
    patchResult(
            owner.cookie(),
            itemId,
            "{\"result\":\"CANCELLED\",\"cancelReasonCode\":\"CUSTOMER_REASON\"}",
            coordinationVersion(ownerCoordination))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.coordinationStatus").value("VISIT_INCOMPLETE"));
    assertThat(requestStatus(ownerRequest)).isEqualTo("CANCELLED");
    assertThat(
            count(
                "SELECT count(*) FROM visit_schedule_item_result_history WHERE schedule_item_id = ?"
                    + " AND cancel_reason_code = 'CUSTOMER_REASON'",
                itemId))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM visit_request_notice WHERE request_id = ? AND notice_type ="
                    + " 'SLOT_REOPENED'",
                agentRequest))
        .isEqualTo(1);
    assertThat(requestStatus(agentRequest)).isEqualTo("TIME_CLOSED");
    assertThat(coordinationStatus(agentCoordination)).isEqualTo("FINAL_CONFIRMATION_REQUIRED");
    assertThat(scheduleItemCount(agentCoordination)).isZero();
    assertThat(transitions(ownerCoordination)).endsWith("CONFIRMED", "VISIT_INCOMPLETE");
  }

  @Test
  void 시나리오14_대리_입력_출처와_409_재조율_중_변경_신청과_같은_날_대체_방문_제외를_지킨다() throws Exception {
    Desk desk = owner();
    long propertyId = newProperty(desk.cookie());
    long approvalId = registerApproval(desk.cookie(), propertyId, null, -1, 30);
    String proxyUrl = approvalsUrl(propertyId) + "/" + approvalId + "/approver-conditions/proxy";
    mockMvc
        .perform(jsonPutAs(proxyUrl, conditionJson(true), desk.cookie(), null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("PROXY"))
        .andExpect(jsonPath("$.versionNo").value(1));
    String token = issueApproverToken(desk.cookie(), propertyId, approvalId);
    mockMvc
        .perform(
            jsonPutAs(
                conditionsUrl(token),
                conditionJson(false, Map.of("maxVisitsPerDay", "1", "minGapMinutes", "120")),
                null,
                null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("DIRECT"))
        .andExpect(jsonPath("$.versionNo").value(2));
    assertError(
        mockMvc.perform(jsonPutAs(proxyUrl, conditionJson(true), desk.cookie(), null)),
        ErrorCode.INVALID_TRANSITION);
    assertThat(
            count(
                "SELECT count(*) FROM visit_approver_condition WHERE approval_id = ? AND status ="
                    + " 'ACTIVE' AND source = 'DIRECT'",
                approvalId))
        .isEqualTo(1);

    LocalDate day = visitDay();
    long coordinationId = coordination(desk, customer(desk, "한변경"), propertyId);
    customerTimes(desk, coordinationId, slot(day, "09:00", "16:00"));
    long candidateId = candidateOf(coordinationId, propertyId);
    compose(desk, coordinationId, candidateId);
    long requestId = submitRequest(desk, coordinationId, candidateId, day, "10:00", "10:30");
    selectNow(token, requestId).andExpect(jsonPath("$.requestStatus").value("CONFIRMED"));
    long itemId = scheduledItem(coordinationId);

    postVersioned(
            coordinationUrl(coordinationId) + "/reschedule",
            "{\"reason\":\"승인자 일정 변경\"}",
            desk.cookie(),
            coordinationVersion(coordinationId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RESCHEDULE_REQUIRED"));

    long changeId =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPostAs(
                            itemUrl(itemId) + "/change-requests",
                            "{\"requestedStartAt\":\""
                                + day
                                + "T10:15:00+09:00\",\"requestedEndAt\":\""
                                + day
                                + "T10:45:00+09:00\",\"expectedDurationMinutes\":30}",
                            desk.cookie()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.requestKind").value("CHANGE"))
                    .andExpect(jsonPath("$.replacesScheduleItemId").value(itemId))
                    .andReturn())
            .get("requestId")
            .longValue();
    assertThat(coordinationStatus(coordinationId)).isEqualTo("RESCHEDULE_REQUIRED");

    selectNow(token, changeId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestStatus").value("CONFIRMED"));
    assertThat(coordinationStatus(coordinationId)).isEqualTo("CONFIRMED");
    assertThat(itemResult(itemId)).isEqualTo("CANCELLED");
    assertThat(
            count(
                "SELECT count(*) FROM visit_schedule_item_result_history WHERE schedule_item_id = ?"
                    + " AND cancel_reason_code = 'CHANGE_REPLACED'",
                itemId))
        .isEqualTo(1);
    assertThat(transitions(coordinationId))
        .containsSubsequence("CONFIRMED", "RESCHEDULE_REQUIRED", "CONFIRMED");
  }

  @Test
  void 시나리오14_희망_시작이_지난_신청은_배치가_만료로_바꾸고_배치_전에_선택하면_409와_만료로_끝난다() throws Exception {
    Desk desk = owner();
    long first = newProperty(desk.cookie());
    long second = newProperty(desk.cookie());
    Approval firstApproval = proxyApproval(desk, first);
    proxyApproval(desk, second);
    LocalDate day = visitDay();
    long coordinationId = coordination(desk, customer(desk, "오만료"), first, second);
    customerTimes(desk, coordinationId, slot(day, "10:00", "16:00"));
    long firstCandidate = candidateOf(coordinationId, first);
    long secondCandidate = candidateOf(coordinationId, second);
    compose(desk, coordinationId, firstCandidate, secondCandidate);
    long selectedLate = submitRequest(desk, coordinationId, firstCandidate, day, "10:00", "10:30");
    long sweptByBatch = submitRequest(desk, coordinationId, secondCandidate, day, "11:00", "11:30");

    jdbcTemplate.update(
        "UPDATE visit_request SET requested_start_at = now() - interval '2 hours',"
            + " requested_end_at = now() - interval '90 minutes' WHERE id IN (?, ?)",
        selectedLate,
        sweptByBatch);

    assertError(
        selectNow(firstApproval.token(), selectedLate), ErrorCode.VISIT_REQUEST_INVALID_STATE);
    assertThat(requestStatus(selectedLate)).isEqualTo("EXPIRED");
    assertThat(
            count(
                "SELECT count(*) FROM visit_request_revalidation WHERE request_id = ?",
                selectedLate))
        .isZero();

    assertThat(expiryService.expireOverdue()).isEqualTo(1);
    assertThat(requestStatus(sweptByBatch)).isEqualTo("EXPIRED");
    assertThat(
            count(
                "SELECT count(*) FROM visit_request_status_history WHERE request_id = ? AND"
                    + " to_status = 'EXPIRED' AND source = 'SYSTEM' AND reason = 'START_PASSED'",
                sweptByBatch))
        .isEqualTo(1);
    assertThat(coordinationStatus(coordinationId)).isEqualTo("FINAL_CONFIRMATION_REQUIRED");
    assertThat(scheduleItemCount(coordinationId)).isZero();
  }
}
