package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitIntegrationTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class VisitCoordinationQueryIntegrationTest extends VisitIntegrationTestBase {

  @Test
  void 목록은_상태별_건수와_필터와_페이지를_돌려주고_최근_수정순이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "이수요");
    long first =
        createCoordination(
                cookie, customer, null, today(), today().plusDays(3), newProperty(cookie))
            .get("coordinationId")
            .longValue();
    long second =
        createCoordination(
                cookie,
                customer,
                null,
                today(),
                today().plusDays(3),
                newProperty(cookie),
                newProperty(cookie))
            .get("coordinationId")
            .longValue();
    long third =
        createCoordination(
                cookie, customer, null, today(), today().plusDays(3), newProperty(cookie))
            .get("coordinationId")
            .longValue();
    setCoordinationStatus(second, "COORDINATABLE");
    setCoordinationStatus(third, "CANCELLED");
    jdbcTemplate.update(
        "UPDATE visit_coordination SET updated_at = now() + interval '1 hour' WHERE id = ?", first);

    mockMvc
        .perform(authedGet("/api/visit-coordinations", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(25))
        .andExpect(jsonPath("$.statusCounts.TIME_COLLECTING").value(1))
        .andExpect(jsonPath("$.statusCounts.COORDINATABLE").value(1))
        .andExpect(jsonPath("$.statusCounts.CANCELLED").value(1))
        .andExpect(jsonPath("$.statusCounts.APPLYING").value(0))
        .andExpect(jsonPath("$.items[0].coordinationId").value(first))
        .andExpect(jsonPath("$.items[0].customerName").value("이수요"))
        .andExpect(jsonPath("$.items[0].managerName").exists())
        .andExpect(jsonPath("$.items[0].candidatePropertyCount").value(1))
        .andExpect(jsonPath("$.items[0].candidateProperties[0].propertyName").exists())
        .andExpect(jsonPath("$.items[0].officeId").value(owner.office().getId()))
        .andExpect(jsonPath("$.items[0].version").value(1));

    mockMvc
        .perform(
            authedGet("/api/visit-coordinations", cookie)
                .param("status", "COORDINATABLE", "CANCELLED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.statusCounts.TIME_COLLECTING").value(1))
        .andExpect(jsonPath("$.items.length()").value(2));
    mockMvc
        .perform(
            authedGet("/api/visit-coordinations", cookie).param("size", "25").param("page", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  @Test
  void 목록의_잘못된_상태_값과_페이지_크기는_400이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);

    assertError(
        mockMvc.perform(authedGet("/api/visit-coordinations", cookie).param("status", "UNKNOWN")),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(authedGet("/api/visit-coordinations", cookie).param("size", "10")),
        ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 목록은_다른_사무소_조율을_보여주지_않는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var foreign = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "이수요");
    createCoordination(cookie, customer, null, today(), today().plusDays(3), newProperty(cookie));

    mockMvc
        .perform(authedGet("/api/visit-coordinations", cookieOf(foreign)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0))
        .andExpect(jsonPath("$.statusCounts.TIME_COLLECTING").value(0));
  }

  @Test
  void 중개보조원의_목록과_건수는_본인에게_배정된_조율만_센다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var deputy = staffIn(owner.office(), OfficeMembershipRole.DEPUTY);
    addAgentToRoster(owner.office().getId());
    Cookie ownerCookie = cookieOf(owner);
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "이수요");
    createCoordination(
        ownerCookie, customer, null, today(), today().plusDays(3), newProperty(ownerCookie));
    long assigned =
        createCoordination(
                ownerCookie,
                customer,
                deputy.membership().getId(),
                today(),
                today().plusDays(3),
                newProperty(ownerCookie))
            .get("coordinationId")
            .longValue();

    mockMvc
        .perform(authedGet("/api/visit-coordinations", cookieOf(deputy)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].coordinationId").value(assigned))
        .andExpect(jsonPath("$.statusCounts.TIME_COLLECTING").value(1));
    mockMvc
        .perform(authedGet("/api/visit-coordinations", ownerCookie))
        .andExpect(jsonPath("$.totalElements").value(2));
  }

  @Test
  void 상세는_후보_매물_고객_응답_링크_상태를_돌려주고_토큰_원문은_포함하지_않는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "이수요");
    long property = newProperty(cookie);
    long id =
        createCoordination(cookie, customer, null, today(), today().plusDays(5), property)
            .get("coordinationId")
            .longValue();

    JsonNode before =
        AuthFlowSupport.json(
            mockMvc
                .perform(authedGet("/api/visit-coordinations/" + id, cookie))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(before.get("status").asString()).isEqualTo("TIME_COLLECTING");
    assertThat(before.get("customerName").asString()).isEqualTo("이수요");
    assertThat(before.get("customerLink").isNull()).isTrue();
    assertThat(before.get("customerRespondedAt").isNull()).isTrue();
    assertThat(before.get("customerTimeSlots")).isEmpty();
    assertThat(before.get("scheduleItems")).isEmpty();
    assertThat(before.get("alternatives")).isEmpty();
    assertThat(before.get("rescheduleAlert").isNull()).isTrue();
    assertThat(before.get("confirmedScheduleVersion").isNull()).isTrue();
    JsonNode candidate = before.get("candidateProperties").get(0);
    assertThat(candidate.get("propertyId").longValue()).isEqualTo(property);
    assertThat(candidate.get("crossOffice").asBoolean()).isFalse();
    assertThat(candidate.get("ownerOfficeId").longValue()).isEqualTo(owner.office().getId());
    assertThat(candidate.get("ownerOfficeName").asString()).isNotBlank();
    assertThat(candidate.get("applicableSlots")).isEmpty();
    assertThat(candidate.get("approverTimeSlots")).isEmpty();

    String token = issueToken(cookie, id);
    String detail =
        mockMvc
            .perform(authedGet("/api/visit-coordinations/" + id, cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.customerLink.responseVersion").value(0))
            .andExpect(jsonPath("$.customerLink.revokedAt").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(detail).doesNotContain(token).doesNotContain("publicUrl").doesNotContain("token");
  }

  @Test
  void 상세는_다른_사무소_404_배정_밖_중개보조원_403_없는_변경_대상_404다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var foreign = newStaff(OfficeMembershipRole.OWNER);
    var deputy = staffIn(owner.office(), OfficeMembershipRole.DEPUTY);
    addAgentToRoster(owner.office().getId());
    Cookie cookie = cookieOf(owner);
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "이수요");
    long id =
        createCoordination(
                cookie, customer, null, today(), today().plusDays(5), newProperty(cookie))
            .get("coordinationId")
            .longValue();

    assertError(
        mockMvc.perform(authedGet("/api/visit-coordinations/" + id, cookieOf(foreign))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(authedGet("/api/visit-coordinations/" + id, cookieOf(deputy))),
        ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(authedGet("/api/visit-coordinations/999999", cookie)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(
            authedGet("/api/visit-coordinations/" + id, cookie).param("changeScheduleItemId", "1")),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(authedGet("/api/visit-coordinations/abc", cookie)),
        ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 로그인이_없으면_401이고_소속이_없는_회원은_403이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "이수요");
    long id =
        createCoordination(
                cookie, customer, null, today(), today().plusDays(5), newProperty(cookie))
            .get("coordinationId")
            .longValue();
    Cookie plain = kakaoSessionFor(saveEmailUser("nomember@example.com", "010-1212-3434"));

    for (String url : new String[] {"/api/visit-coordinations", "/api/visit-coordinations/" + id}) {
      assertError(mockMvc.perform(authedGet(url, null)), ErrorCode.UNAUTHENTICATED);
      assertError(mockMvc.perform(authedGet(url, plain)), ErrorCode.FORBIDDEN);
    }
  }

  @Test
  void 배정된_중개보조원은_본인_조율_상세를_볼_수_있고_고객_안내_조회도_열린다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var deputy = staffIn(owner.office(), OfficeMembershipRole.DEPUTY);
    addAgentToRoster(owner.office().getId());
    Cookie ownerCookie = cookieOf(owner);
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "이수요");
    long id =
        createCoordination(
                ownerCookie,
                customer,
                deputy.membership().getId(),
                today(),
                today().plusDays(5),
                newProperty(ownerCookie))
            .get("coordinationId")
            .longValue();
    Cookie deputyCookie = cookieOf(deputy);

    mockMvc
        .perform(authedGet("/api/visit-coordinations/" + id, deputyCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.managerMembershipId").value(deputy.membership().getId()));
    mockMvc
        .perform(authedGet("/api/customers/" + customer + "/assistant-view", deputyCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("이수요"));
    long unassignedCustomer =
        insertCustomer(owner.office().getId(), owner.membership().getId(), "미배정");
    assertError(
        mockMvc.perform(
            authedGet("/api/customers/" + unassignedCustomer + "/assistant-view", deputyCookie)),
        ErrorCode.FORBIDDEN);
  }
}
