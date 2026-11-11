package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitSelectionTestBase;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class VisitScheduleIntegrationTest extends VisitSelectionTestBase {

  private static String scheduleUrl(long coordinationId) {
    return "/api/visit-coordinations/" + coordinationId + "/schedule";
  }

  private static String item(long candidateId, int order, int visit, int travel) {
    return "{\"candidatePropertyId\":"
        + candidateId
        + ",\"sortOrder\":"
        + order
        + ",\"visitDurationMinutes\":"
        + visit
        + ",\"travelDurationMinutes\":"
        + travel
        + "}";
  }

  private static String items(String... items) {
    return "{\"items\":[" + String.join(",", items) + "]}";
  }

  private static java.time.Instant at(LocalDate day, String time) {
    return ZonedDateTime.parse(day + "T" + time + ":00+09:00").toInstant();
  }

  private static java.time.Instant instant(JsonNode node) {
    return OffsetDateTime.parse(node.asString()).toInstant();
  }

  private ResultActions put(RequestFixture f, String body, Object version) throws Exception {
    return mockMvc.perform(jsonPutAs(scheduleUrl(f.coordinationId()), body, f.cookie(), version));
  }

  private long confirmedCoordination(SelectionFixture s) throws Exception {
    selectNow(s.token(), s.requestId()).andExpect(status().isOk());
    return s.coordinationId();
  }

  private long coordinationWithBusyItem(
      SelectionFixture s, String from, String to, String travelEnd) throws Exception {
    long property = newProperty(s.cookie());
    long customer =
        insertCustomer(
            s.base().owner().office().getId(), s.base().owner().membership().getId(), "박다른");
    long coordination =
        createCoordination(s.cookie(), customer, null, today(), today().plusDays(10), property)
            .get("coordinationId")
            .longValue();
    long candidate =
        jdbcTemplate.queryForObject(
            "SELECT id FROM visit_candidate_property WHERE coordination_id = ?",
            Long.class,
            coordination);
    insertBusyItem(
        coordination,
        candidate,
        s.base().owner().office().getId(),
        today().plusDays(3),
        from,
        to,
        travelEnd);
    return coordination;
  }

  @Test
  void 일정을_구성하면_순서와_소요시간이_저장되고_시작_시각_후보를_돌려준다() throws Exception {
    RequestFixture f = requestFixture();
    ExtraCandidate extra = addCandidateWithApproval(f);
    setCoordinationStatus(f.coordinationId(), "COORDINATABLE");
    long version = coordinationVersion(f.coordinationId());
    LocalDate day = today().plusDays(3);

    JsonNode response =
        AuthFlowSupport.json(
            put(
                    f,
                    items(item(extra.candidateId(), 1, 45, 10), item(f.candidateId(), 2, 30, 0)),
                    version)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINAL_CONFIRMATION_REQUIRED"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].candidatePropertyId").value(extra.candidateId()))
                .andExpect(jsonPath("$.items[0].sortOrder").value(1))
                .andExpect(jsonPath("$.officeId").value(f.owner().office().getId()))
                .andReturn());

    assertThat(instant(response.get("items").get(0).get("suggestedStartAt")))
        .isEqualTo(at(day, "10:00"));
    assertThat(instant(response.get("items").get(0).get("suggestedEndAt")))
        .isEqualTo(at(day, "10:45"));
    assertThat(instant(response.get("items").get(1).get("suggestedStartAt")))
        .isEqualTo(at(day, "10:55"));
    assertThat(response.get("version").longValue()).isGreaterThan(version);
    assertThat(
            jdbcTemplate.queryForMap(
                "SELECT sort_order, visit_duration_minutes, travel_duration_minutes FROM"
                    + " visit_candidate_property WHERE id = ?",
                extra.candidateId()))
        .containsEntry("sort_order", 1)
        .containsEntry("visit_duration_minutes", 45)
        .containsEntry("travel_duration_minutes", 10);
    assertThat(coordinationStatus(f.coordinationId())).isEqualTo("FINAL_CONFIRMATION_REQUIRED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM visit_coordination_status_history WHERE coordination_id = ?"
                    + " AND from_status = 'COORDINATABLE' AND to_status ="
                    + " 'FINAL_CONFIRMATION_REQUIRED' AND source = 'STAFF'",
                Long.class,
                f.coordinationId()))
        .isEqualTo(1L);
  }

  @Test
  void 일정_구성은_If_Match가_필수이고_상태와_범위를_검사한다() throws Exception {
    RequestFixture f = requestFixture();
    String body = items(item(f.candidateId(), 1, 30, 15));
    long version = coordinationVersion(f.coordinationId());

    put(f, body, null).andExpect(status().isBadRequest());
    assertError(put(f, body, version + 5), ErrorCode.VERSION_CONFLICT);
    put(f, body, version).andExpect(status().isOk());
    assertThat(coordinationStatus(f.coordinationId())).isEqualTo("FINAL_CONFIRMATION_REQUIRED");

    for (String state : List.of("TIME_COLLECTING", "APPLYING", "CONFIRMED", "CANCELLED")) {
      setCoordinationStatus(f.coordinationId(), state);
      assertError(
          put(f, body, coordinationVersion(f.coordinationId())), ErrorCode.INVALID_TRANSITION);
    }
  }

  @Test
  void 출입승인이_없거나_허용_구간이_없으면_422다() throws Exception {
    RequestFixture noApproval = requestFixture(newStaff(OfficeMembershipRole.OWNER), false, false);
    assertError(
        put(
            noApproval,
            items(item(noApproval.candidateId(), 1, 30, 15)),
            coordinationVersion(noApproval.coordinationId())),
        ErrorCode.TIME_OUT_OF_RANGE);

    RequestFixture f = requestFixture();
    ExtraCandidate extra = addCandidateWithApproval(f);
    assertError(
        put(
            f,
            items(item(f.candidateId(), 1, 240, 15), item(extra.candidateId(), 2, 240, 0)),
            coordinationVersion(f.coordinationId())),
        ErrorCode.TIME_OUT_OF_RANGE);
    assertThat(coordinationStatus(f.coordinationId())).isEqualTo("FINAL_CONFIRMATION_REQUIRED");
  }

  @Test
  void 일정_구성_입력과_권한과_다른_사무소를_검사한다() throws Exception {
    RequestFixture f = requestFixture();
    long version = coordinationVersion(f.coordinationId());
    String url = scheduleUrl(f.coordinationId());

    put(f, "{\"items\":[],\"x\":1}", version).andExpect(status().isBadRequest());
    put(f, "{\"items\":[]}", version).andExpect(status().isBadRequest());
    put(f, items(item(f.candidateId(), 1, 3, 15)), version).andExpect(status().isBadRequest());
    put(f, items(item(f.candidateId(), 1, 30, 300)), version).andExpect(status().isBadRequest());
    put(f, items(item(f.candidateId(), 1, 30, 15), item(f.candidateId(), 2, 30, 15)), version)
        .andExpect(status().isBadRequest());
    assertError(put(f, items(item(987654L, 1, 30, 15)), version), ErrorCode.RESOURCE_NOT_FOUND);

    assertError(
        mockMvc.perform(jsonPutAs(url, items(item(f.candidateId(), 1, 30, 15)), null, version)),
        ErrorCode.UNAUTHENTICATED);
    var stranger = newStaff(OfficeMembershipRole.OWNER);
    assertError(
        mockMvc.perform(
            jsonPutAs(url, items(item(f.candidateId(), 1, 30, 15)), cookieOf(stranger), version)),
        ErrorCode.RESOURCE_NOT_FOUND);
    var deputy = staffIn(f.owner().office(), OfficeMembershipRole.DEPUTY);
    assertError(
        mockMvc.perform(
            jsonPutAs(url, items(item(f.candidateId(), 1, 30, 15)), cookieOf(deputy), version)),
        ErrorCode.FORBIDDEN);
  }

  @Test
  void 충돌_사전_확인은_담당자_고객_매물_충돌을_구분하고_접근_밖_상대는_가린다() throws Exception {
    SelectionFixture s = selectionFixture();
    coordinationWithBusyItem(s, "10:10", "10:40", null);
    long customerOverlap = coordinationWithBusyItem(s, "10:20", "10:50", null);
    jdbcTemplate.update(
        "UPDATE visit_coordination SET customer_id = ? WHERE id = ?",
        s.base().customer(),
        customerOverlap);
    var other = newStaff(OfficeMembershipRole.OWNER);
    long otherCustomer = insertCustomer(other.office().getId(), other.membership().getId(), "외부고객");
    TestStaffRef otherRef = new TestStaffRef(other.office().getId(), other.membership().getId());
    long otherCandidate =
        insertCoordinationWithCandidate(
            otherRef,
            otherCustomer,
            s.base().propertyId(),
            s.base().owner().office().getId(),
            "CONFIRMED");
    insertBusyItem(
        coordinationOfCandidate(otherCandidate),
        otherCandidate,
        other.office().getId(),
        today().plusDays(3),
        "10:05",
        "10:25",
        null);

    JsonNode body =
        AuthFlowSupport.json(
            mockMvc
                .perform(authedGet(scheduleUrl(s.coordinationId()) + "/conflicts", s.cookie()))
                .andExpect(status().isOk())
                .andReturn());

    List<String> types = new ArrayList<>();
    JsonNode propertyOverlap = null;
    for (JsonNode conflict : body.get("conflicts")) {
      types.add(conflict.get("conflictType").asString());
      assertThat(conflict.get("candidatePropertyId").longValue()).isEqualTo(s.candidateId());
      if ("PROPERTY_OVERLAP".equals(conflict.get("conflictType").asString())) {
        propertyOverlap = conflict;
      }
    }
    assertThat(types).contains("MANAGER_OVERLAP", "CUSTOMER_OVERLAP", "PROPERTY_OVERLAP");
    assertThat(propertyOverlap).isNotNull();
    assertThat(propertyOverlap.get("conflictingCoordinationId").isNull()).isTrue();
    assertThat(propertyOverlap.get("conflictingPropertyName").isNull()).isTrue();
    assertThat(body.get("conflicts").get(0).get("conflictingStartAt").asString()).isNotBlank();
  }

  @Test
  void 충돌이_없으면_빈_배열이고_권한과_다른_사무소를_검사한다() throws Exception {
    SelectionFixture s = selectionFixture();
    String url = scheduleUrl(s.coordinationId()) + "/conflicts";

    mockMvc
        .perform(authedGet(url, s.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.conflicts.length()").value(0));
    assertError(mockMvc.perform(authedGet(url, null)), ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(authedGet(url, cookieOf(newStaff(OfficeMembershipRole.OWNER)))),
        ErrorCode.RESOURCE_NOT_FOUND);
    var deputy = staffIn(s.base().owner().office(), OfficeMembershipRole.DEPUTY);
    assertError(mockMvc.perform(authedGet(url, cookieOf(deputy))), ErrorCode.FORBIDDEN);
  }

  private ResultActions patchManager(
      Cookie cookie, long coordinationId, Object body, Object version) throws Exception {
    return mockMvc.perform(
        jsonPatchAs(
            "/api/visit-coordinations/" + coordinationId + "/manager",
            "{\"managerMembershipId\":" + body + "}",
            cookie,
            version));
  }

  @Test
  void 담당자를_바꾸면_기록되고_고객_링크가_폐기되며_영향_방문_목록을_돌려준다() throws Exception {
    RequestFixture f = requestFixture();
    var agent = staffIn(f.owner().office(), OfficeMembershipRole.AGENT);
    long version = coordinationVersion(f.coordinationId());

    patchManager(f.cookie(), f.coordinationId(), agent.membership().getId(), version)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.coordinationId").value(f.coordinationId()))
        .andExpect(jsonPath("$.managerMembershipId").value(agent.membership().getId()))
        .andExpect(jsonPath("$.affectedItems.length()").value(0));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT manager_membership_id FROM visit_coordination WHERE id = ?",
                Long.class,
                f.coordinationId()))
        .isEqualTo(agent.membership().getId());
    assertThat(coordinationVersion(f.coordinationId())).isGreaterThan(version);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM visit_customer_link WHERE coordination_id = ? AND"
                    + " revoked_at IS NULL",
                Long.class,
                f.coordinationId()))
        .isZero();
  }

  @Test
  void 담당자_변경_입력과_권한과_상태를_검사한다() throws Exception {
    RequestFixture f = requestFixture();
    var agent = staffIn(f.owner().office(), OfficeMembershipRole.AGENT);
    var deputy = staffIn(f.owner().office(), OfficeMembershipRole.DEPUTY);
    var stranger = newStaff(OfficeMembershipRole.OWNER);
    long version = coordinationVersion(f.coordinationId());
    long target = agent.membership().getId();

    patchManager(f.cookie(), f.coordinationId(), target, null).andExpect(status().isBadRequest());
    assertError(
        patchManager(f.cookie(), f.coordinationId(), target, version + 3),
        ErrorCode.VERSION_CONFLICT);
    assertError(
        patchManager(cookieOf(deputy), f.coordinationId(), target, version), ErrorCode.FORBIDDEN);
    assertError(patchManager(null, f.coordinationId(), target, version), ErrorCode.UNAUTHENTICATED);
    assertError(
        patchManager(cookieOf(stranger), f.coordinationId(), target, version),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        patchManager(f.cookie(), f.coordinationId(), stranger.membership().getId(), version),
        ErrorCode.RESOURCE_NOT_FOUND);
    patchManager(f.cookie(), f.coordinationId(), deputy.membership().getId(), version)
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            jsonPatchAs(
                "/api/visit-coordinations/" + f.coordinationId() + "/manager",
                "{\"managerMembershipId\":" + target + ",\"x\":1}",
                f.cookie(),
                version))
        .andExpect(status().isBadRequest());
    for (String state : List.of("VISIT_COMPLETED", "VISIT_INCOMPLETE", "CANCELLED")) {
      setCoordinationStatus(f.coordinationId(), state);
      assertError(
          patchManager(
              f.cookie(), f.coordinationId(), target, coordinationVersion(f.coordinationId())),
          ErrorCode.INVALID_TRANSITION);
    }
  }

  @Test
  void 새_담당자의_확정_방문과_겹치면_담당자를_바꿀_수_없다() throws Exception {
    SelectionFixture s = selectionFixture();
    confirmedCoordination(s);
    var agent = staffIn(s.base().owner().office(), OfficeMembershipRole.AGENT);
    long property = newProperty(s.cookie());
    long customer =
        insertCustomer(
            s.base().owner().office().getId(), s.base().owner().membership().getId(), "박다른");
    long coordination =
        createCoordination(
                cookieOf(agent),
                customer,
                agent.membership().getId(),
                today(),
                today().plusDays(10),
                property)
            .get("coordinationId")
            .longValue();
    long candidate =
        jdbcTemplate.queryForObject(
            "SELECT id FROM visit_candidate_property WHERE coordination_id = ?",
            Long.class,
            coordination);
    insertBusyItem(
        coordination,
        candidate,
        s.base().owner().office().getId(),
        today().plusDays(3),
        "10:10",
        "10:40",
        null);

    assertError(
        patchManager(
            s.cookie(),
            s.coordinationId(),
            agent.membership().getId(),
            coordinationVersion(s.coordinationId())),
        ErrorCode.SCHEDULE_CONFLICT);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT manager_membership_id FROM visit_coordination WHERE id = ?",
                Long.class,
                s.coordinationId()))
        .isEqualTo(s.base().owner().membership().getId());
  }

  @Test
  void 확정_안내문은_확정_뒤에만_받고_고객_전체_동선과_승인자_개별_문구를_담는다() throws Exception {
    SelectionFixture s = selectionFixture();
    String url = "/api/visit-coordinations/" + s.coordinationId() + "/confirmation-messages";

    assertError(mockMvc.perform(authedGet(url, s.cookie())), ErrorCode.INVALID_TRANSITION);
    confirmedCoordination(s);

    JsonNode body =
        AuthFlowSupport.json(
            mockMvc
                .perform(authedGet(url, s.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleVersion").value(1))
                .andExpect(jsonPath("$.approverMessages.length()").value(1))
                .andExpect(
                    jsonPath("$.approverMessages[0].candidatePropertyId").value(s.candidateId()))
                .andExpect(
                    jsonPath("$.approverMessages[0].approvalId").value(s.base().approvalId()))
                .andExpect(jsonPath("$.approverMessages[0].approverName").value("김현소"))
                .andReturn());
    assertThat(body.get("customerMessage").asString()).contains("방문 일정 안내").contains("10:00~10:30");
    assertThat(body.get("approverMessages").get(0).get("message").asString())
        .contains("10:00~10:30")
        .doesNotContain("이수요");
    assertError(mockMvc.perform(authedGet(url, null)), ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(authedGet(url, cookieOf(newStaff(OfficeMembershipRole.OWNER)))),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 담당자가_중개보조원이면_고객_안내문에_신분_문구가_들어간다() throws Exception {
    SelectionFixture s = selectionFixture();
    confirmedCoordination(s);
    var deputy = staffIn(s.base().owner().office(), OfficeMembershipRole.DEPUTY);
    jdbcTemplate.update(
        "UPDATE visit_coordination SET manager_membership_id = ? WHERE id = ?",
        deputy.membership().getId(),
        s.coordinationId());

    mockMvc
        .perform(
            authedGet(
                "/api/visit-coordinations/" + s.coordinationId() + "/confirmation-messages",
                s.cookie()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.customerMessage").value(org.hamcrest.Matchers.containsString("중개보조원입니다")));
  }

  private ResultActions delivery(Cookie cookie, long coordinationId, String body) throws Exception {
    return mockMvc.perform(
        jsonPostAs(
            "/api/visit-coordinations/" + coordinationId + "/confirmation-deliveries",
            body,
            cookie));
  }

  @Test
  void 전달_기록은_확정_일정_버전별로_추가되고_목록에_이전_버전도_남는다() throws Exception {
    SelectionFixture s = selectionFixture();
    ExtraCandidate extra = addCandidateWithApproval(s.base());
    long extraRequest =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPostAs(
                            requestUrl(s.coordinationId()),
                            requestBody(extra.candidateId(), today().plusDays(3), "12:00", "12:30"),
                            s.cookie()))
                    .andExpect(status().isCreated())
                    .andReturn())
            .get("requestId")
            .longValue();
    String extraToken = issueApproverToken(s.cookie(), extra.propertyId(), extra.approvalId());
    selectNow(s.token(), s.requestId()).andExpect(status().isOk());
    selectNow(extraToken, extraRequest).andExpect(status().isOk());
    assertThat(coordinationStatus(s.coordinationId())).isEqualTo("CONFIRMED");
    String listUrl = "/api/visit-coordinations/" + s.coordinationId() + "/confirmation-deliveries";
    String at = OffsetDateTime.now().minusMinutes(5).toString();

    mockMvc
        .perform(
            authedGet(
                "/api/visit-coordinations/" + s.coordinationId() + "/confirmation-messages",
                s.cookie()))
        .andExpect(jsonPath("$.scheduleVersion").value(2));
    assertError(
        delivery(
            s.cookie(),
            s.coordinationId(),
            "{\"scheduleVersion\":1,\"recipientType\":\"CUSTOMER\",\"delivered\":false}"),
        ErrorCode.INVALID_TRANSITION);
    delivery(
            s.cookie(),
            s.coordinationId(),
            "{\"scheduleVersion\":2,\"recipientType\":\"CUSTOMER\",\"delivered\":true,"
                + "\"channel\":\"KAKAO\",\"deliveredAt\":\""
                + at
                + "\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.scheduleVersion").value(2))
        .andExpect(jsonPath("$.recipientType").value("CUSTOMER"))
        .andExpect(jsonPath("$.delivered").value(true))
        .andExpect(jsonPath("$.channel").value("KAKAO"))
        .andExpect(jsonPath("$.candidatePropertyId").doesNotExist())
        .andExpect(jsonPath("$.officeId").value(s.base().owner().office().getId()));
    delivery(
            s.cookie(),
            s.coordinationId(),
            "{\"scheduleVersion\":2,\"recipientType\":\"APPROVER\",\"candidatePropertyId\":"
                + s.candidateId()
                + ",\"delivered\":false}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.delivered").value(false))
        .andExpect(jsonPath("$.channel").doesNotExist())
        .andExpect(jsonPath("$.deliveredAt").doesNotExist());

    mockMvc
        .perform(authedGet(listUrl, s.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[*].recipientType", hasItem("APPROVER")));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM visit_confirmation_delivery WHERE coordination_id = ?",
                Long.class,
                s.coordinationId()))
        .isEqualTo(2L);
  }

  @Test
  void 전달_기록_입력_검증과_권한을_검사한다() throws Exception {
    SelectionFixture s = selectionFixture();
    long id = s.coordinationId();
    assertError(
        delivery(
            s.cookie(),
            id,
            "{\"scheduleVersion\":1,\"recipientType\":\"CUSTOMER\",\"delivered\":false}"),
        ErrorCode.INVALID_TRANSITION);
    confirmedCoordination(s);
    String now = OffsetDateTime.now().minusMinutes(1).toString();

    delivery(
            s.cookie(),
            id,
            "{\"scheduleVersion\":1,\"recipientType\":\"CUSTOMER\",\"delivered\":true}")
        .andExpect(status().isBadRequest());
    delivery(
            s.cookie(),
            id,
            "{\"scheduleVersion\":1,\"recipientType\":\"CUSTOMER\",\"delivered\":false,\"channel\":\"SMS\"}")
        .andExpect(status().isBadRequest());
    delivery(
            s.cookie(),
            id,
            "{\"scheduleVersion\":1,\"recipientType\":\"CUSTOMER\",\"delivered\":true,\"channel\":\"FAX\",\"deliveredAt\":\""
                + now
                + "\"}")
        .andExpect(status().isBadRequest());
    delivery(
            s.cookie(),
            id,
            "{\"scheduleVersion\":1,\"recipientType\":\"CUSTOMER\",\"candidatePropertyId\":"
                + s.candidateId()
                + ",\"delivered\":false}")
        .andExpect(status().isBadRequest());
    delivery(
            s.cookie(),
            id,
            "{\"scheduleVersion\":1,\"recipientType\":\"APPROVER\",\"delivered\":false}")
        .andExpect(status().isBadRequest());
    delivery(
            s.cookie(),
            id,
            "{\"scheduleVersion\":1,\"recipientType\":\"OTHER\",\"delivered\":false}")
        .andExpect(status().isBadRequest());
    assertError(
        delivery(
            s.cookie(),
            id,
            "{\"scheduleVersion\":1,\"recipientType\":\"APPROVER\",\"candidatePropertyId\":987654,\"delivered\":false}"),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        delivery(
            null, id, "{\"scheduleVersion\":1,\"recipientType\":\"CUSTOMER\",\"delivered\":false}"),
        ErrorCode.UNAUTHENTICATED);
    assertError(
        delivery(
            cookieOf(newStaff(OfficeMembershipRole.OWNER)),
            id,
            "{\"scheduleVersion\":1,\"recipientType\":\"CUSTOMER\",\"delivered\":false}"),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM visit_confirmation_delivery", Long.class))
        .isZero();
  }

  @Test
  void 조율_상세는_신청_가능_구간에_중개사_일정을_반영하고_확정_뒤_일정_항목을_채운다() throws Exception {
    SelectionFixture s = selectionFixture();
    coordinationWithBusyItem(s, "11:00", "12:00", null);
    LocalDate day = today().plusDays(3);

    JsonNode detail =
        AuthFlowSupport.json(
            mockMvc
                .perform(authedGet("/api/visit-coordinations/" + s.coordinationId(), s.cookie()))
                .andExpect(status().isOk())
                .andReturn());
    JsonNode slots = detail.get("candidateProperties").get(0).get("applicableSlots");
    assertThat(slots.size()).isEqualTo(2);
    assertThat(instant(slots.get(0).get("startsAt"))).isEqualTo(at(day, "10:00"));
    assertThat(instant(slots.get(0).get("endsAt"))).isEqualTo(at(day, "11:00"));
    assertThat(instant(slots.get(1).get("startsAt"))).isEqualTo(at(day, "12:00"));
    assertThat(instant(slots.get(1).get("endsAt"))).isEqualTo(at(day, "16:00"));

    jdbcTemplate.update("DELETE FROM visit_schedule_item WHERE confirmed_request_id IS NULL");
    selectNow(s.token(), s.requestId()).andExpect(status().isOk());
    mockMvc
        .perform(authedGet("/api/visit-coordinations/" + s.coordinationId(), s.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.confirmedScheduleVersion").value(1))
        .andExpect(jsonPath("$.scheduleItems.length()").value(1))
        .andExpect(jsonPath("$.scheduleItems[0].result").value("SCHEDULED"))
        .andExpect(jsonPath("$.scheduleItems[0].state").value("VALID"))
        .andExpect(jsonPath("$.scheduleItems[0].candidatePropertyId").value(s.candidateId()))
        .andExpect(jsonPath("$.candidateProperties[0].applicableSlots.length()").value(0));
  }

  @Test
  void 신청_제출은_담당_중개사의_확정_방문과_이동_구간_충돌도_위반으로_본다() throws Exception {
    RequestFixture f = requestFixture();
    SelectionFixture holder = new SelectionFixture(f, 0, "");
    coordinationWithBusyItem(holder, "10:10", "10:40", null);

    mockMvc
        .perform(jsonPostAs(requestUrl(f.coordinationId()), validBody(f), f.cookie()))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("APPROVER_CONDITION_VIOLATED"))
        .andExpect(jsonPath("$.violatedRules", hasItem("BROKER_CONFLICT")));

    jdbcTemplate.update("DELETE FROM visit_schedule_item");
    coordinationWithBusyItem(holder, "09:20", "09:50", "10:10");
    mockMvc
        .perform(jsonPostAs(requestUrl(f.coordinationId()), validBody(f), f.cookie()))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.violatedRules", hasItem("TRAVEL_CONFLICT")));
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM visit_request", Long.class))
        .isZero();
  }
}
