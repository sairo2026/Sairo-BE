package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitOutcomeTestBase;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ApplyGrantIntegrationTest extends VisitOutcomeTestBase {

  private record Grantee(TestData.Staff staff, Cookie cookie) {

    long officeId() {
      return staff.office().getId();
    }

    long membershipId() {
      return staff.membership().getId();
    }
  }

  private record Granted(RequestFixture owner, Grantee grantee, long grantId) {}

  private record CrossRequest(Granted granted, long coordinationId, long requestId) {}

  private static String grantsUrl(RequestFixture f) {
    return "/api/properties/"
        + f.propertyId()
        + "/access-approvals/"
        + f.approvalId()
        + "/apply-grants";
  }

  private Grantee grantee() {
    TestData.Staff staff = newStaff(OfficeMembershipRole.OWNER);
    verify(staff);
    return new Grantee(staff, cookieOf(staff));
  }

  private void verify(TestData.Staff staff) {
    jdbcTemplate.update(
        "INSERT INTO office_registration (applicant_user_id, status, entity_type,"
            + " registration_authority, real_estate_license_number, business_registration_number,"
            + " requested_office_name, requested_representative_name,"
            + " requested_representative_phone, requested_address, applicant_registration_relation,"
            + " resulting_office_id, reviewed_at) VALUES (?, 'APPROVED', 'INDIVIDUAL', '서울', ?,"
            + " '1234567890', '사무소', '홍대표', '010-0000-0000', '서울', '대표', ?, now())",
        staff.user().getId(),
        "LIC-" + System.nanoTime(),
        staff.office().getId());
  }

  private JsonNode grant(RequestFixture f, String body) throws Exception {
    return AuthFlowSupport.json(
        mockMvc
            .perform(jsonPostAs(grantsUrl(f), body, f.cookie()))
            .andExpect(status().isCreated())
            .andReturn());
  }

  private Granted granted() throws Exception {
    RequestFixture owner = requestFixture();
    Grantee grantee = grantee();
    long grantId =
        grant(owner, "{\"granteeOfficeId\":" + grantee.officeId() + "}").get("grantId").longValue();
    return new Granted(owner, grantee, grantId);
  }

  private CrossRequest crossRequest() throws Exception {
    Granted g = granted();
    long customer = insertCustomer(g.grantee().officeId(), g.grantee().membershipId(), "박수요");
    long coordinationId =
        createCoordination(
                g.grantee().cookie(),
                customer,
                null,
                today(),
                today().plusDays(10),
                g.owner().propertyId())
            .get("coordinationId")
            .longValue();
    long candidateId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM visit_candidate_property WHERE coordination_id = ?",
            Long.class,
            coordinationId);
    submitCustomerSlots(g.grantee().cookie(), coordinationId, visitDay(), 10, 16);
    setCoordinationStatus(coordinationId, "FINAL_CONFIRMATION_REQUIRED");
    long requestId =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPostAs(
                            requestUrl(coordinationId),
                            requestBody(candidateId, visitDay(), "10:00", "10:30"),
                            g.grantee().cookie()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.crossOffice").value(true))
                    .andExpect(jsonPath("$.accessGrantId").value(g.grantId()))
                    .andReturn())
            .get("requestId")
            .longValue();
    return new CrossRequest(g, coordinationId, requestId);
  }

  @Test
  void 대표_출입승인에_다른_사무소_허용을_부여하고_목록으로_본다() throws Exception {
    RequestFixture owner = requestFixture();
    Grantee grantee = grantee();

    mockMvc
        .perform(
            jsonPostAs(
                grantsUrl(owner),
                "{\"granteeOfficeId\":" + grantee.officeId() + "}",
                owner.cookie()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.approvalId").value(owner.approvalId()))
        .andExpect(jsonPath("$.propertyId").value(owner.propertyId()))
        .andExpect(jsonPath("$.ownerOfficeId").value(owner.owner().office().getId()))
        .andExpect(jsonPath("$.granteeOfficeId").value(grantee.officeId()))
        .andExpect(jsonPath("$.scope").value("OFFICE"))
        .andExpect(jsonPath("$.granteeMembershipId").doesNotExist())
        .andExpect(jsonPath("$.grantedByMembershipId").value(owner.owner().membership().getId()))
        .andExpect(jsonPath("$.state").value("ACTIVE"))
        .andExpect(jsonPath("$.openRequestCount").value(0));

    mockMvc
        .perform(authedGet(grantsUrl(owner), owner.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].state").value("ACTIVE"));
    mockMvc
        .perform(authedGet(grantsUrl(owner) + "?state=REVOKED", owner.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
    assertThat(
            count(
                "SELECT count(*) FROM visit_property_apply_grant_event WHERE event_type ="
                    + " 'GRANTED' AND source = 'OFFICE'"))
        .isEqualTo(1);
  }

  @Test
  void 같은_대상의_활성_허용이_있으면_409이고_사무소_전체_허용은_중개사_허용을_막는다() throws Exception {
    Granted g = granted();

    assertError(
        mockMvc.perform(
            jsonPostAs(
                grantsUrl(g.owner()),
                "{\"granteeOfficeId\":" + g.grantee().officeId() + "}",
                g.owner().cookie())),
        ErrorCode.APPLY_GRANT_DUPLICATE_ACTIVE);
    assertError(
        mockMvc.perform(
            jsonPostAs(
                grantsUrl(g.owner()),
                "{\"granteeOfficeId\":"
                    + g.grantee().officeId()
                    + ",\"granteeMembershipId\":"
                    + g.grantee().membershipId()
                    + "}",
                g.owner().cookie())),
        ErrorCode.APPLY_GRANT_DUPLICATE_ACTIVE);
  }

  @Test
  void 중개사_한_명_허용이_있어도_사무소_전체_허용을_만든다() throws Exception {
    RequestFixture owner = requestFixture();
    Grantee grantee = grantee();
    grant(
            owner,
            "{\"granteeOfficeId\":"
                + grantee.officeId()
                + ",\"granteeMembershipId\":"
                + grantee.membershipId()
                + "}")
        .get("scope");

    mockMvc
        .perform(
            jsonPostAs(
                grantsUrl(owner),
                "{\"granteeOfficeId\":" + grantee.officeId() + "}",
                owner.cookie()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.scope").value("OFFICE"));
  }

  @Test
  void 허용_대상이_잘못되면_422이고_만료_시각이_범위_밖이면_400이다() throws Exception {
    RequestFixture owner = requestFixture();
    TestData.Staff unverified = newStaff(OfficeMembershipRole.OWNER);
    Grantee grantee = grantee();
    TestData.Staff deputy = staffIn(grantee.staff().office(), OfficeMembershipRole.DEPUTY);

    assertError(
        mockMvc.perform(
            jsonPostAs(
                grantsUrl(owner),
                "{\"granteeOfficeId\":" + owner.owner().office().getId() + "}",
                owner.cookie())),
        ErrorCode.APPLY_GRANT_TARGET_INVALID);
    assertError(
        mockMvc.perform(
            jsonPostAs(
                grantsUrl(owner),
                "{\"granteeOfficeId\":" + unverified.office().getId() + "}",
                owner.cookie())),
        ErrorCode.APPLY_GRANT_TARGET_INVALID);
    assertError(
        mockMvc.perform(
            jsonPostAs(
                grantsUrl(owner),
                "{\"granteeOfficeId\":"
                    + grantee.officeId()
                    + ",\"granteeMembershipId\":"
                    + deputy.membership().getId()
                    + "}",
                owner.cookie())),
        ErrorCode.APPLY_GRANT_TARGET_INVALID);
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                grantsUrl(owner),
                "{\"granteeOfficeId\":"
                    + grantee.officeId()
                    + ",\"expiresAt\":\""
                    + today().plusDays(90)
                    + "T00:00:00+09:00\"}",
                owner.cookie())),
        "expiresAt",
        "OUT_OF_RANGE");
  }

  @Test
  void 공동_승인자_출입승인과_철회된_출입승인에는_허용할_수_없다() throws Exception {
    RequestFixture owner = requestFixture();
    Grantee grantee = grantee();
    long coApproval = registerApproval(owner.cookie(), owner.propertyId(), "CO_APPROVER", -1, 30);

    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/properties/"
                    + owner.propertyId()
                    + "/access-approvals/"
                    + coApproval
                    + "/apply-grants",
                "{\"granteeOfficeId\":" + grantee.officeId() + "}",
                owner.cookie())),
        ErrorCode.INVALID_TRANSITION);
    jdbcTemplate.update(
        "UPDATE property_access_approval SET revoked_at = now(), revocation_reason = '철회',"
            + " revocation_recorded_at = now(), revoked_by_user_id = ? WHERE id = ?",
        owner.owner().user().getId(),
        owner.approvalId());
    assertError(
        mockMvc.perform(
            jsonPostAs(
                grantsUrl(owner),
                "{\"granteeOfficeId\":" + grantee.officeId() + "}",
                owner.cookie())),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 허용_경로는_로그인_역할_사무소_경계를_검사한다() throws Exception {
    Granted g = granted();
    RequestFixture owner = g.owner();
    Cookie deputy = cookieOf(staffIn(owner.owner().office(), OfficeMembershipRole.DEPUTY));
    Cookie other = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    String body = "{\"granteeOfficeId\":" + g.grantee().officeId() + "}";
    String revokeUrl = grantsUrl(owner) + "/" + g.grantId() + "/revoke";
    String revokeBody = "{\"reason\":\"회수\"}";

    assertError(
        mockMvc.perform(jsonPostAs(grantsUrl(owner), body, null)), ErrorCode.UNAUTHENTICATED);
    assertError(mockMvc.perform(get(grantsUrl(owner))), ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(jsonPostAs(revokeUrl, revokeBody, null)), ErrorCode.UNAUTHENTICATED);
    assertError(mockMvc.perform(get("/api/apply-grants/received")), ErrorCode.UNAUTHENTICATED);
    assertError(mockMvc.perform(jsonPostAs(grantsUrl(owner), body, deputy)), ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(authedGet(grantsUrl(owner), deputy)), ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(jsonPostAs(revokeUrl, revokeBody, deputy)), ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(authedGet("/api/apply-grants/received", deputy)), ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(jsonPostAs(grantsUrl(owner), body, other)), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(mockMvc.perform(authedGet(grantsUrl(owner), other)), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(jsonPostAs(revokeUrl, revokeBody, other)), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(
            jsonPostAs(
                grantsUrl(owner) + "/" + (g.grantId() + 1000) + "/revoke",
                revokeBody,
                owner.cookie())),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 허용받은_사무소는_매물_목록을_보고_후보로_담아_신청한다() throws Exception {
    Granted g = granted();
    Cookie stranger = cookieOf(newStaff(OfficeMembershipRole.OWNER));

    mockMvc
        .perform(authedGet("/api/apply-grants/received", g.grantee().cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].grantId").value(g.grantId()))
        .andExpect(jsonPath("$.items[0].propertyId").value(g.owner().propertyId()))
        .andExpect(jsonPath("$.items[0].scope").value("OFFICE"))
        .andExpect(jsonPath("$.items[0].conditionRegistered").value(true))
        .andExpect(jsonPath("$.items[0].dealTypes").isArray())
        .andExpect(jsonPath("$.items[0].propertyAddressDetail").doesNotExist())
        .andExpect(jsonPath("$.items[0].approverName").doesNotExist());
    mockMvc
        .perform(authedGet("/api/apply-grants/received", stranger))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));

    CrossRequest c = crossRequestFrom(g);
    mockMvc
        .perform(authedGet("/api/visit-coordinations/" + c.coordinationId(), g.grantee().cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.candidateProperties[0].crossOffice").value(true))
        .andExpect(jsonPath("$.candidateProperties[0].grantId").value(g.grantId()))
        .andExpect(jsonPath("$.candidateProperties[0].grantState").value("ACTIVE"))
        .andExpect(jsonPath("$.candidateProperties[0].propertyAddressDetail").doesNotExist());
  }

  @Test
  void 허용이_없는_다른_사무소_매물은_후보로_담을_수_없다() throws Exception {
    RequestFixture owner = requestFixture();
    Grantee grantee = grantee();
    long customer = insertCustomer(grantee.officeId(), grantee.membershipId(), "박수요");

    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/visit-coordinations",
                createBody(
                    customer,
                    null,
                    today(),
                    today().plusDays(10),
                    java.util.List.of(owner.propertyId())),
                grantee.cookie())),
        ErrorCode.CANDIDATE_NOT_ALLOWED);
  }

  @Test
  void 회수하면_허용_근거_신청_대기_신청을_만료로_종결하고_다시_회수하면_409이다() throws Exception {
    CrossRequest c = crossRequest();
    Granted g = c.granted();
    String revokeUrl = grantsUrl(g.owner()) + "/" + g.grantId() + "/revoke";

    mockMvc
        .perform(jsonPostAs(revokeUrl, "{\"reason\":\"계약 협의가 끝났다\"}", g.owner().cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.grant.state").value("REVOKED"))
        .andExpect(jsonPath("$.grant.revokeSource").value("OFFICE"))
        .andExpect(jsonPath("$.grant.revokeReason").value("계약 협의가 끝났다"))
        .andExpect(jsonPath("$.closedRequestIds[0]").value(c.requestId()))
        .andExpect(jsonPath("$.keptScheduleItemIds").isEmpty());

    assertThat(requestStatus(c.requestId())).isEqualTo("EXPIRED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT reason FROM visit_request_status_history WHERE request_id = ? AND"
                    + " to_status = 'EXPIRED'",
                String.class,
                c.requestId()))
        .isEqualTo("GRANT_REVOKED");
    assertThat(
            count(
                "SELECT count(*) FROM visit_request_notice WHERE request_id = ? AND notice_type ="
                    + " 'GRANT_REVOKED'",
                c.requestId()))
        .isEqualTo(1);
    assertThat(coordinationStatus(c.coordinationId())).isEqualTo("FINAL_CONFIRMATION_REQUIRED");
    assertThat(
            count(
                "SELECT affected_request_count FROM visit_property_apply_grant_event WHERE"
                    + " grant_id = ? AND event_type = 'REVOKED'",
                g.grantId()))
        .isEqualTo(1);

    assertError(
        mockMvc.perform(jsonPostAs(revokeUrl, "{\"reason\":\"다시\"}", g.owner().cookie())),
        ErrorCode.INVALID_TRANSITION);
    mockMvc
        .perform(authedGet("/api/apply-grants/received", g.grantee().cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
    mockMvc
        .perform(
            authedGet(
                "/api/properties/" + g.owner().propertyId() + "/cross-office-requests",
                g.owner().cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].requestId").value(c.requestId()))
        .andExpect(jsonPath("$.items[0].status").value("EXPIRED"))
        .andExpect(jsonPath("$.items[0].closeReasonCode").value("GRANT_REVOKED"));
  }

  @Test
  void 출입승인_철회를_접수하면_허용이_만료되고_신청_대기_신청은_GRANT_EXPIRED로_종결한다() throws Exception {
    CrossRequest c = crossRequest();
    RequestFixture owner = c.granted().owner();

    postVersioned(
            "/api/visit-coordinations/" + owner.coordinationId() + "/access-revocations",
            "{\"approvalId\":"
                + owner.approvalId()
                + ",\"revokedAt\":\""
                + minutesAgo(5)
                + "\",\"revocationReason\":\"점유자 요청\"}",
            owner.cookie(),
            coordinationVersion(owner.coordinationId()))
        .andExpect(status().isOk());

    assertThat(requestStatus(c.requestId())).isEqualTo("EXPIRED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT reason FROM visit_request_status_history WHERE request_id = ? AND"
                    + " to_status = 'EXPIRED'",
                String.class,
                c.requestId()))
        .isEqualTo("GRANT_EXPIRED");
    assertThat(
            count(
                "SELECT count(*) FROM visit_property_apply_grant_event WHERE grant_id = ? AND"
                    + " event_type = 'EXPIRED' AND source = 'SYSTEM'",
                c.granted().grantId()))
        .isEqualTo(1);
    mockMvc
        .perform(authedGet(grantsUrl(owner), owner.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].state").value("EXPIRED"))
        .andExpect(jsonPath("$.items[0].revokedAt").doesNotExist());
  }

  @Test
  void 매물을_보관하면_허용을_회수하고_신청_대기_신청을_상태_이력과_알림을_남겨_종결한다() throws Exception {
    CrossRequest c = crossRequest();
    Granted g = c.granted();
    RequestFixture owner = g.owner();
    cancel(owner.cookie(), owner.coordinationId(), "{\"reason\":\"매물 거래 종료\"}")
        .andExpect(status().isOk());

    mockMvc
        .perform(deleteAs("/api/properties/" + owner.propertyId(), owner.cookie()))
        .andExpect(status().isNoContent());

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT revoke_reason FROM visit_property_apply_grant WHERE id = ? AND"
                    + " revoke_source = 'SYSTEM' AND revoked_at IS NOT NULL",
                String.class,
                g.grantId()))
        .isEqualTo("PROPERTY_ARCHIVED");
    assertThat(requestStatus(c.requestId())).isEqualTo("EXPIRED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT reason FROM visit_request_status_history WHERE request_id = ? AND"
                    + " from_status = 'PENDING' AND to_status = 'EXPIRED' AND source = 'SYSTEM'",
                String.class,
                c.requestId()))
        .isEqualTo("GRANT_REVOKED");
    assertThat(
            count(
                "SELECT count(*) FROM visit_request_notice WHERE request_id = ? AND notice_type ="
                    + " 'GRANT_REVOKED'",
                c.requestId()))
        .isEqualTo(1);
    assertThat(coordinationStatus(c.coordinationId())).isEqualTo("FINAL_CONFIRMATION_REQUIRED");
    assertThat(
            count(
                "SELECT affected_request_count FROM visit_property_apply_grant_event WHERE"
                    + " grant_id = ? AND event_type = 'REVOKED' AND source = 'SYSTEM' AND reason ="
                    + " 'PROPERTY_ARCHIVED'",
                g.grantId()))
        .isEqualTo(1);
  }

  @Test
  void 매물을_가진_사무소는_다른_사무소_신청과_확정_방문을_읽기_전용으로_본다() throws Exception {
    CrossRequest c = crossRequest();
    RequestFixture owner = c.granted().owner();
    String requestsUrl = "/api/properties/" + owner.propertyId() + "/cross-office-requests";
    String schedulesUrl = "/api/properties/" + owner.propertyId() + "/cross-office-schedules";

    mockMvc
        .perform(authedGet(requestsUrl, owner.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].requestKind").value("NEW"))
        .andExpect(jsonPath("$.items[0].status").value("PENDING"))
        .andExpect(jsonPath("$.items[0].expectedDurationMinutes").value(30))
        .andExpect(jsonPath("$.items[0].closeReasonCode").doesNotExist())
        .andExpect(jsonPath("$.items[0].coordinationId").doesNotExist())
        .andExpect(jsonPath("$.items[0].requestNote").doesNotExist());

    String token = issueApproverToken(owner.cookie(), owner.propertyId(), owner.approvalId());
    selectNow(token, c.requestId()).andExpect(status().isOk());

    JsonNode schedules =
        AuthFlowSupport.json(
            mockMvc
                .perform(authedGet(schedulesUrl, owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].result").value("SCHEDULED"))
                .andExpect(jsonPath("$.items[0].state").value("VALID"))
                .andExpect(jsonPath("$.items[0].confirmedRequestId").value(c.requestId()))
                .andReturn());
    assertThat(schedules.get("items").get(0).has("customerName")).isFalse();
    mockMvc
        .perform(authedGet(schedulesUrl + "?result=CANCELLED", owner.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
    mockMvc
        .perform(
            authedGet(
                schedulesUrl + "?from=" + visitDay().plusDays(1) + "&to=" + visitDay().plusDays(2),
                owner.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
    mockMvc
        .perform(authedGet(requestsUrl + "?status=PENDING", owner.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));

    Cookie deputy = cookieOf(staffIn(owner.owner().office(), OfficeMembershipRole.DEPUTY));
    assertError(mockMvc.perform(get(requestsUrl)), ErrorCode.UNAUTHENTICATED);
    assertError(mockMvc.perform(get(schedulesUrl)), ErrorCode.UNAUTHENTICATED);
    assertError(mockMvc.perform(authedGet(requestsUrl, deputy)), ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(authedGet(schedulesUrl, deputy)), ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(authedGet(requestsUrl, c.granted().grantee().cookie())),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(authedGet(schedulesUrl, c.granted().grantee().cookie())),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertFieldError(
        mockMvc.perform(authedGet(schedulesUrl + "?result=DONE", owner.cookie())),
        "result",
        "NOT_ALLOWED");
  }

  private CrossRequest crossRequestFrom(Granted g) throws Exception {
    long customer = insertCustomer(g.grantee().officeId(), g.grantee().membershipId(), "박수요");
    long coordinationId =
        createCoordination(
                g.grantee().cookie(),
                customer,
                null,
                today(),
                today().plusDays(10),
                g.owner().propertyId())
            .get("coordinationId")
            .longValue();
    return new CrossRequest(g, coordinationId, 0);
  }
}
