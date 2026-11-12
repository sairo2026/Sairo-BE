package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.ApproverAccountTestBase;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * 개발플로우 4장 필수 시나리오 ⑮ 다른 사무소 신청, ⑯ 승인자 계정, ⑰ 날짜별 필수 조건을 실제 엔드포인트 순서로 잇는 출시 검증 시험이다. 사무소 인증 심사 통과는
 * 시나리오 범위 밖 전제라 심사 승인 행으로 둔다.
 */
@IntegrationTest
class LaunchScenarioVisitExtensionIntegrationTest extends ApproverAccountTestBase {

  private static final String RAW_PHONE_DIGITS = "01011112222";

  private record Desk(TestData.Staff staff, Cookie cookie) {

    long officeId() {
      return staff.office().getId();
    }

    long membershipId() {
      return staff.membership().getId();
    }
  }

  private Desk owner() {
    TestData.Staff staff = newStaff(OfficeMembershipRole.OWNER);
    return new Desk(staff, cookieOf(staff));
  }

  private Desk verifiedOwner() {
    Desk desk = owner();
    jdbcTemplate.update(
        "INSERT INTO office_registration (applicant_user_id, status, entity_type,"
            + " registration_authority, real_estate_license_number, business_registration_number,"
            + " requested_office_name, requested_representative_name,"
            + " requested_representative_phone, requested_address, applicant_registration_relation,"
            + " resulting_office_id, reviewed_at) VALUES (?, 'APPROVED', 'INDIVIDUAL', '서울', ?,"
            + " '1234567890', '사무소', '홍대표', '010-0000-0000', '서울', '대표', ?, now())",
        desk.staff().user().getId(),
        "LIC-" + System.nanoTime(),
        desk.officeId());
    return desk;
  }

  private long customer(Desk desk, String name) throws Exception {
    return createCustomer(desk.cookie(), name, null, desk.membershipId());
  }

  private String linkCondition(String token, String body) throws Exception {
    return AuthFlowSupport.json(
            mockMvc
                .perform(jsonPutAs(conditionsUrl(token), body, null, null))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andReturn())
        .toString();
  }

  private long coordination(Desk desk, long customerId, long propertyId) throws Exception {
    JsonNode created =
        createCoordination(
            desk.cookie(), customerId, null, today(), today().plusDays(10), propertyId);
    assertThat(created.get("status").asString()).isEqualTo("TIME_COLLECTING");
    return created.get("coordinationId").longValue();
  }

  private long candidateOf(long coordinationId) {
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT id FROM visit_candidate_property WHERE coordination_id = ?",
            Long.class,
            coordinationId);
    return id == null ? -1 : id;
  }

  private static String slot(LocalDate day, String from, LocalDate endDay, String to) {
    return "{\"startsAt\":\""
        + day
        + "T"
        + from
        + ":00+09:00\",\"endsAt\":\""
        + endDay
        + "T"
        + to
        + ":00+09:00\"}";
  }

  private static String slot(LocalDate day, String from, String to) {
    return slot(day, from, day, to);
  }

  /** 고객 시간 제출과 일정 구성을 거쳐 신청을 받을 수 있는 조율 건을 만들고 후보 ID를 돌려준다. */
  private long readyForRequests(Desk desk, long coordinationId, String... slots) throws Exception {
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
    long candidateId = candidateOf(coordinationId);
    mockMvc
        .perform(
            jsonPutAs(
                "/api/visit-coordinations/" + coordinationId + "/schedule",
                "{\"items\":[{\"candidatePropertyId\":"
                    + candidateId
                    + ",\"sortOrder\":1,\"visitDurationMinutes\":30,\"travelDurationMinutes\":0}]}",
                desk.cookie(),
                coordinationVersion(coordinationId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FINAL_CONFIRMATION_REQUIRED"));
    return candidateId;
  }

  private ResultActions submitRaw(
      Desk desk, long coordinationId, LocalDate day, String from, String to, String extra)
      throws Exception {
    return mockMvc.perform(
        jsonPostAs(
            requestUrl(coordinationId),
            requestBody(candidateOf(coordinationId), day, from, to, "30", extra),
            desk.cookie()));
  }

  private long submitRequest(
      Desk desk, long coordinationId, LocalDate day, String from, String to, String extra)
      throws Exception {
    return AuthFlowSupport.json(
            submitRaw(desk, coordinationId, day, from, to, extra)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn())
        .get("requestId")
        .longValue();
  }

  private long submitRequest(Desk desk, long coordinationId, LocalDate day, String from, String to)
      throws Exception {
    return submitRequest(desk, coordinationId, day, from, to, "");
  }

  private long rowsContaining(String table, String text) {
    return count(
        "SELECT count(*) FROM " + table + " t WHERE row_to_json(t)::text LIKE ?", "%" + text + "%");
  }

  private int selectStatus(String token, long requestId, long version) throws Exception {
    return select(token, requestId, version, selectBody(requestId))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Test
  void 시나리오15_허용받은_다른_사무소만_후보로_담아_신청하고_동시_확정은_한_건이며_회수하면_대기_신청이_만료된다() throws Exception {
    Desk holder = owner();
    Desk grantee = verifiedOwner();
    Desk outsider = verifiedOwner();
    long propertyId = newProperty(holder.cookie());
    long approvalId = registerApproval(holder.cookie(), propertyId, null, -1, 30);
    String token = issueApproverToken(holder.cookie(), propertyId, approvalId);
    linkCondition(token, conditionJson(false));
    String grantsUrl =
        "/api/properties/" + propertyId + "/access-approvals/" + approvalId + "/apply-grants";

    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/visit-coordinations",
                createBody(
                    customer(outsider, "외부고객"),
                    null,
                    today(),
                    today().plusDays(10),
                    List.of(propertyId)),
                outsider.cookie())),
        ErrorCode.CANDIDATE_NOT_ALLOWED);

    long grantId =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPostAs(
                            grantsUrl,
                            "{\"granteeOfficeId\":" + grantee.officeId() + "}",
                            holder.cookie()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.state").value("ACTIVE"))
                    .andReturn())
            .get("grantId")
            .longValue();

    LocalDate day = visitDay();
    long granteeCoordination = coordination(grantee, customer(grantee, "허용고객"), propertyId);
    readyForRequests(grantee, granteeCoordination, slot(day, "10:00", "16:00"));
    long granteeRequest =
        AuthFlowSupport.json(
                submitRaw(
                        grantee,
                        granteeCoordination,
                        day,
                        "10:00",
                        "10:30",
                        ",\"partySize\":2,\"requestNote\":\"주차 문의\"")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.crossOffice").value(true))
                    .andExpect(jsonPath("$.accessGrantId").value(grantId))
                    .andReturn())
            .get("requestId")
            .longValue();
    long holderCoordination = coordination(holder, customer(holder, "소유고객"), propertyId);
    readyForRequests(holder, holderCoordination, slot(day, "10:00", "16:00"));
    long holderRequest = submitRequest(holder, holderCoordination, day, "10:00", "10:30");

    compare(token).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));

    long granteeVersion = requestVersion(granteeRequest);
    long holderVersion = requestVersion(holderRequest);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Integer>> tasks = new ArrayList<>();
    tasks.add(
        () -> {
          start.await();
          return selectStatus(token, granteeRequest, granteeVersion);
        });
    tasks.add(
        () -> {
          start.await();
          return selectStatus(token, holderRequest, holderVersion);
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
    assertThat(List.of(requestStatus(granteeRequest), requestStatus(holderRequest)))
        .containsExactlyInAnyOrder("CONFIRMED", "TIME_CLOSED");
    assertThat(
            count(
                "SELECT count(*) FROM visit_schedule_item i JOIN visit_candidate_property c ON"
                    + " c.id = i.candidate_property_id WHERE c.property_id = ? AND i.result ="
                    + " 'SCHEDULED'",
                propertyId))
        .isEqualTo(1);

    long laterCoordination = coordination(grantee, customer(grantee, "나중고객"), propertyId);
    readyForRequests(grantee, laterCoordination, slot(day, "10:00", "16:00"));
    long laterRequest = submitRequest(grantee, laterCoordination, day, "14:00", "14:30");

    JsonNode crossView =
        AuthFlowSupport.json(
            mockMvc
                .perform(
                    authedGet(
                        "/api/properties/" + propertyId + "/cross-office-requests",
                        holder.cookie()))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(crossView.get("items").size()).isGreaterThanOrEqualTo(2);
    for (JsonNode item : crossView.get("items")) {
      assertThat(item.has("customerName")).isFalse();
      assertThat(item.has("partySize")).isFalse();
      assertThat(item.has("requestNote")).isFalse();
      assertThat(item.has("coordinationId")).isFalse();
    }
    assertThat(crossView.toString()).doesNotContain("허용고객").doesNotContain("주차 문의");
    assertError(
        mockMvc.perform(
            authedGet(
                "/api/properties/" + propertyId + "/cross-office-requests", grantee.cookie())),
        ErrorCode.RESOURCE_NOT_FOUND);

    mockMvc
        .perform(
            jsonPostAs(
                grantsUrl + "/" + grantId + "/revoke",
                "{\"reason\":\"계약 협의 종료\"}",
                holder.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.grant.state").value("REVOKED"))
        .andExpect(jsonPath("$.closedRequestIds", hasItem((int) laterRequest)));
    assertThat(requestStatus(laterRequest)).isEqualTo("EXPIRED");
    assertThat(
            count(
                "SELECT count(*) FROM visit_request_notice WHERE request_id = ? AND notice_type ="
                    + " 'GRANT_REVOKED'",
                laterRequest))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM visit_schedule_item i JOIN visit_candidate_property c ON"
                    + " c.id = i.candidate_property_id WHERE c.property_id = ? AND i.result ="
                    + " 'SCHEDULED'",
                propertyId))
        .isEqualTo(1);
    assertThat(coordinationStatus(laterCoordination)).isEqualTo("FINAL_CONFIRMATION_REQUIRED");

    assertError(
        submitRaw(grantee, laterCoordination, day, "15:00", "15:30", ""),
        ErrorCode.APPLY_GRANT_INACTIVE);
    assertThat(
            count(
                "SELECT count(*) FROM visit_request WHERE applicant_office_id = ?",
                outsider.officeId()))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM visit_request WHERE applicant_office_id <> property_office_id"
                    + " AND access_grant_id IS NULL"))
        .isZero();
  }

  @Test
  void 시나리오16_승인자가_번호_인증으로_계정을_만들고_연결_제안을_골라_일괄_적용하며_공동_승인자는_읽기_전용이고_해지_뒤에도_링크는_유지된다()
      throws Exception {
    Desk desk = owner();
    long mainProperty = newProperty(desk.cookie());
    long mainApproval = registerApproval(desk.cookie(), mainProperty, null, -1, 30);
    long secondApproval = registerApproval(desk.cookie(), newProperty(desk.cookie()), null, -1, 30);
    long coApproval = registerApproval(desk.cookie(), mainProperty, "CO_APPROVER", -1, 30);
    long rejectedApproval =
        registerApproval(desk.cookie(), newProperty(desk.cookie()), null, -1, 30);
    String token = issueApproverToken(desk.cookie(), mainProperty, mainApproval);
    linkCondition(token, conditionJson(false));

    LocalDate day = visitDay();
    long coordinationId = coordination(desk, customer(desk, "윤승인"), mainProperty);
    readyForRequests(desk, coordinationId, slot(day, "10:00", "16:00"));
    long requestId = submitRequest(desk, coordinationId, day, "10:00", "10:30");

    sendOtp("CLAIM", PHONE, token, null)
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"));
    MvcResult claimed =
        verifyOtp("CLAIM", PHONE, flow.lastSmsCode(PHONE), token, NOTICE_VERSION, null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.profileCreated").value(true))
            .andExpect(jsonPath("$.claimedApprovalId").value(mainApproval))
            .andExpect(jsonPath("$.pendingConfirmCount").value(3))
            .andExpect(jsonPath("$.maskedPhone").value("***-****-2222"))
            .andReturn();
    assertThat(claimed.getResponse().getContentAsString())
        .doesNotContain(PHONE)
        .doesNotContain(RAW_PHONE_DIGITS);
    Cookie cookie = approverCookie(claimed);

    for (long approval : new long[] {secondApproval, coApproval}) {
      mockMvc
          .perform(
              jsonPostAs(
                  portalApproval(approval) + "/link-decision", "{\"decision\":\"ACCEPT\"}", cookie))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.linkState").value("LINKED"));
    }
    mockMvc
        .perform(
            jsonPostAs(
                portalApproval(rejectedApproval) + "/link-decision",
                "{\"decision\":\"REJECT\"}",
                cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.linkState").value("REJECTED"));
    JsonNode linked =
        AuthFlowSupport.json(
            portalGet(PORTAL + "/approvals?linkState=LINKED", cookie)
                .andExpect(status().isOk())
                .andReturn());
    List<Long> linkedIds = new ArrayList<>();
    linked.get("items").forEach(item -> linkedIds.add(item.get("approvalId").longValue()));
    assertThat(linkedIds).containsExactlyInAnyOrder(mainApproval, secondApproval, coApproval);
    assertError(
        portalGet(portalApproval(rejectedApproval) + "/conditions", cookie),
        ErrorCode.RESOURCE_NOT_FOUND);

    assertError(
        mockMvc.perform(
            jsonPutAs(
                portalApproval(coApproval) + "/conditions", conditionJson(false), cookie, null)),
        ErrorCode.APPROVER_READ_ONLY);
    MockHttpServletRequestBuilder coSelect =
        jsonPostAs(
            portalApproval(coApproval) + "/visit-requests/" + requestId + "/select",
            selectBody(requestId),
            cookie);
    coSelect.header("If-Match", String.valueOf(requestVersion(requestId)));
    assertError(mockMvc.perform(coSelect), ErrorCode.APPROVER_READ_ONLY);
    assertThat(requestStatus(requestId)).isEqualTo("PENDING");

    MockHttpServletRequestBuilder mainSelect =
        jsonPostAs(
            portalApproval(mainApproval) + "/visit-requests/" + requestId + "/select",
            selectBody(requestId),
            cookie);
    mainSelect.header("If-Match", String.valueOf(requestVersion(requestId)));
    mockMvc
        .perform(mainSelect)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestStatus").value("CONFIRMED"));
    assertThat(coordinationStatus(coordinationId)).isEqualTo("CONFIRMED");
    long mainConditionBefore = activeConditionId(mainApproval);

    Map<String, String> strict = new HashMap<>();
    strict.put("impactAcknowledged", null);
    strict.put("acknowledgedNoticeVersion", null);
    strict.put("windows", windows("05:00", "06:00"));
    mockMvc
        .perform(
            jsonPostAs(
                PORTAL + "/condition-bulk",
                "{\"approvalIds\":["
                    + mainApproval
                    + ","
                    + secondApproval
                    + "],\"condition\":"
                    + conditionJson(false, strict)
                    + ",\"acknowledgedNoticeVersion\":\""
                    + NOTICE_VERSION
                    + "\"}",
                cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestedCount").value(2))
        .andExpect(jsonPath("$.appliedCount").value(1))
        .andExpect(jsonPath("$.needsImpactAckCount").value(1))
        .andExpect(jsonPath("$.items[0].result").value("NEEDS_IMPACT_ACK"))
        .andExpect(jsonPath("$.items[1].result").value("APPLIED"));
    assertThat(activeConditionId(mainApproval)).isEqualTo(mainConditionBefore);
    assertThat(coordinationStatus(coordinationId)).isEqualTo("CONFIRMED");
    long profileId = profileId();
    assertThat(
            count(
                "SELECT count(*) FROM visit_approver_condition WHERE approval_id = ? AND status ="
                    + " 'ACTIVE' AND source = 'DIRECT' AND entered_by_profile_id = ?",
                secondApproval,
                profileId))
        .isEqualTo(1);

    sendOtp("REAUTH", PHONE, null, cookie).andExpect(status().isOk());
    verifyOtp("REAUTH", PHONE, flow.lastSmsCode(PHONE), null, null, cookie)
        .andExpect(status().isOk());
    mockMvc
        .perform(jsonPostAs(PORTAL + "/profile/close", "{\"confirm\":true}", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.unlinkedApprovalCount").value(3));
    assertThat(
            count(
                "SELECT count(*) FROM approver_profile WHERE id = ? AND status = 'CLOSED' AND"
                    + " phone_hash IS NULL AND phone_last4 IS NULL",
                profileId))
        .isEqualTo(1);
    assertError(portalGet(PORTAL + "/profile", cookie), ErrorCode.UNAUTHENTICATED);
    mockMvc
        .perform(authedGet(conditionsUrl(token), null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approvalId").value(mainApproval));

    for (String table :
        List.of(
            "approver_profile",
            "approver_profile_approval",
            "approver_auth_event",
            "approver_condition_bulk_batch",
            "approver_condition_bulk_item",
            "consent_guest_notice_ack",
            "visit_request_selection",
            "visit_approver_property_link")) {
      assertThat(rowsContaining(table, PHONE)).as(table).isZero();
      assertThat(rowsContaining(table, RAW_PHONE_DIGITS)).as(table).isZero();
    }
    assertThat(rowsContaining("visit_approver_property_link", token)).isZero();
  }

  @Test
  void 시나리오17_날짜별_조건이_신청_가능_구간과_제출_검증과_선택_재검증에_쓰이고_최소_사전_신청은_제출에만_검사된다() throws Exception {
    Desk desk = owner();
    long propertyId = newProperty(desk.cookie());
    long approvalId = registerApproval(desk.cookie(), propertyId, null, -1, 30);
    String token = issueApproverToken(desk.cookie(), propertyId, approvalId);
    LocalDate limitedDay = visitDay();
    LocalDate openDay = limitedDay.plusDays(1);
    LocalDate leadDay = limitedDay.plusDays(2);
    String rules =
        "[{\"ruleDate\":\""
            + limitedDay
            + "\",\"maxVisitsPerDay\":{\"kind\":\"VALUE\",\"value\":1},"
            + "\"minGapMinutes\":{\"kind\":\"VALUE\",\"value\":120}},"
            + "{\"ruleDate\":\""
            + openDay
            + "\",\"maxVisitsPerDay\":{\"kind\":\"UNLIMITED\",\"value\":null},"
            + "\"minGapMinutes\":{\"kind\":\"VALUE\",\"value\":10}},"
            + "{\"ruleDate\":\""
            + leadDay
            + "\",\"minLeadMinutes\":{\"kind\":\"VALUE\",\"value\":20160}}]";
    Map<String, String> base = new HashMap<>();
    base.put("windows", windows("00:00", "24:00"));
    base.put("dateRules", rules);
    linkCondition(token, conditionJson(false, base));
    mockMvc
        .perform(authedGet(conditionsUrl(token), null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.maxVisitsPerDay").value(3))
        .andExpect(jsonPath("$.minGapMinutes").value(30))
        .andExpect(jsonPath("$.minLeadMinutes").doesNotExist())
        .andExpect(jsonPath("$.singleVisitMaxMinutes").doesNotExist())
        .andExpect(jsonPath("$.dateRules.length()").value(3))
        .andExpect(jsonPath("$.dateRules[1].maxVisitsPerDay.kind").value("UNLIMITED"));

    long late = coordination(desk, customer(desk, "늦은고객"), propertyId);
    readyForRequests(desk, late, slot(limitedDay, "22:00", openDay, "00:00"));
    long daytime = coordination(desk, customer(desk, "낮고객"), propertyId);
    readyForRequests(
        desk,
        daytime,
        slot(limitedDay, "10:00", "12:00"),
        slot(openDay, "10:00", "12:00"),
        slot(leadDay, "10:00", "12:00"));
    long night = coordination(desk, customer(desk, "새벽고객"), propertyId);
    readyForRequests(desk, night, slot(openDay, "00:00", "03:00"));

    long lateRequest = submitRequest(desk, late, limitedDay, "23:00", "23:30");
    long daytimeRequest = submitRequest(desk, daytime, limitedDay, "10:00", "10:30");
    selectNow(token, lateRequest)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestStatus").value("CONFIRMED"))
        .andExpect(jsonPath("$.timeClosedRequestIds.length()").value(0));
    assertThat(requestStatus(daytimeRequest)).isEqualTo("PENDING");

    JsonNode detail =
        AuthFlowSupport.json(
            mockMvc
                .perform(authedGet("/api/visit-coordinations/" + daytime, desk.cookie()))
                .andExpect(status().isOk())
                .andReturn());
    JsonNode slots = detail.get("candidateProperties").get(0).get("applicableSlots");
    assertThat(slots.size()).isPositive();
    for (JsonNode applicable : slots) {
      LocalDate startDay =
          OffsetDateTime.parse(applicable.get("startsAt").asString())
              .atZoneSameInstant(KST)
              .toLocalDate();
      assertThat(startDay).isEqualTo(openDay);
    }

    selectNow(token, daytimeRequest)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestStatus").value("REVALIDATION_FAILED"))
        .andExpect(jsonPath("$.revalidation.result").value("FAILED"))
        .andExpect(jsonPath("$.revalidation.failureCode").value("MAX_VISITS_PER_DAY"));
    assertThat(coordinationStatus(daytime)).isEqualTo("FINAL_CONFIRMATION_REQUIRED");

    submitRaw(desk, daytime, limitedDay, "11:00", "11:30", "")
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.violatedRules", hasItem("MAX_VISITS_PER_DAY")));
    submitRaw(desk, daytime, leadDay, "10:00", "10:30", "")
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.violatedRules", hasItem("MIN_LEAD")));

    submitRaw(desk, night, openDay, "00:30", "01:00", "")
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.violatedRules.length()").value(1))
        .andExpect(jsonPath("$.violatedRules[0]").value("MIN_GAP"));
    long nightRequest = submitRequest(desk, night, openDay, "01:30", "02:00");
    selectNow(token, nightRequest).andExpect(jsonPath("$.requestStatus").value("CONFIRMED"));

    long openDayRequest = submitRequest(desk, daytime, openDay, "10:00", "10:30");
    String tightened =
        rules.replace(
            "\"minGapMinutes\":{\"kind\":\"VALUE\",\"value\":10}}",
            "\"minGapMinutes\":{\"kind\":\"VALUE\",\"value\":10},"
                + "\"minLeadMinutes\":{\"kind\":\"VALUE\",\"value\":20160}}");
    Map<String, String> changed = new HashMap<>(base);
    changed.put("dateRules", tightened);
    linkCondition(token, conditionJson(false, changed));
    assertThat(
            count(
                "SELECT count(*) FROM visit_approver_condition WHERE approval_id = ?", approvalId))
        .isEqualTo(2);

    selectNow(token, openDayRequest)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestStatus").value("CONFIRMED"))
        .andExpect(jsonPath("$.revalidation.result").value("PASSED"));
    assertThat(coordinationStatus(daytime)).isEqualTo("CONFIRMED");
    assertThat(
            count(
                "SELECT count(*) FROM visit_schedule_item i JOIN visit_candidate_property c ON"
                    + " c.id = i.candidate_property_id WHERE c.property_id = ? AND i.result ="
                    + " 'SCHEDULED'",
                propertyId))
        .isEqualTo(3);
  }

  private static String windows(String from, String to) {
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
}
