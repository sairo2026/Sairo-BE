package com.sairo.be.domain.office.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPatch;
import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.Office;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.domain.office.service.OfficeWithdrawalService;
import com.sairo.be.domain.office.service.OwnerTransferService;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsIntegrationTestBase;
import com.sairo.be.support.fixture.AppUserBuilder;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@IntegrationTest
class OfficeMemberIntegrationTest extends OpsIntegrationTestBase {

  @Autowired private OfficeRepository officeRepository;
  @Autowired private OfficeMembershipRepository officeMembershipRepository;
  @Autowired private OwnerTransferService transferService;
  @Autowired private OfficeWithdrawalService withdrawalService;

  private TestData.Staff owner;
  private Cookie ownerCookie;
  private Office office;

  @BeforeEach
  void setUp() {
    owner = data().approvedStaff(OfficeMembershipRole.OWNER);
    office = owner.office();
    ownerCookie = cookieOf(owner);
    jdbcTemplate.update(
        "INSERT INTO office_registration (applicant_user_id, status, entity_type,"
            + " registration_authority, real_estate_license_number, business_registration_number,"
            + " requested_office_name, requested_representative_name,"
            + " requested_representative_phone, requested_address, applicant_registration_relation,"
            + " resulting_office_id, reviewed_at) SELECT ?, 'APPROVED', entity_type,"
            + " registration_authority, real_estate_license_number, business_registration_number,"
            + " name, representative_name, representative_phone, address, '대표', id, now()"
            + " FROM office WHERE id = ?",
        owner.user().getId(),
        office.getId());
  }

  private TestData data() {
    return new TestData(appUserRepository, officeRepository, officeMembershipRepository);
  }

  private Cookie cookieOf(TestData.Staff staff) {
    return sessions.cookie(
        sessions.createMemberSession(staff.principal(), Instant.now(), Instant.now()));
  }

  private TestData.Staff staffIn(OfficeMembershipRole role) {
    return data()
        .approvedStaffIn(
            office, appUserRepository.saveAndFlush(AppUserBuilder.anAppUser().build()), role);
  }

  private ResultActions post(String url, String body, Cookie cookie) throws Exception {
    return mockMvc.perform(cookie == null ? jsonPost(url, body) : jsonPost(url, body, cookie));
  }

  private ResultActions post(String url, String body) throws Exception {
    return post(url, body, null);
  }

  private ResultActions patch(String url, String body, Long ifMatch, Cookie cookie)
      throws Exception {
    MockHttpServletRequestBuilder builder =
        cookie == null ? jsonPatch(url, body) : jsonPatch(url, body, cookie);
    if (ifMatch != null) {
      builder.header("If-Match", String.valueOf(ifMatch));
    }
    return mockMvc.perform(builder);
  }

  private long officeVersion() {
    return jdbcTemplate.queryForObject(
        "SELECT version FROM office WHERE id = ?", Long.class, office.getId());
  }

  private String rosterBody(String name, String qualification) {
    return "{\"personName\":\""
        + name
        + "\",\"legalQualification\":\""
        + qualification
        + "\",\"employmentStartDate\":\"2026-01-01\",\"confirmationSource\":\"MANUAL_ENTRY\"}";
  }

  private long transferTo(TestData.Staff candidate) throws Exception {
    grantStepUp(ownerCookie);
    var result =
        post(
                "/api/offices/current/owner-transfer",
                "{\"toUserId\":" + candidate.user().getId() + "}",
                ownerCookie)
            .andExpect(status().isCreated())
            .andReturn();
    return AuthFlowSupport.json(result).get("transferId").asLong();
  }

  private String transferStatus(long id) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM office_owner_transfer WHERE id = ?", String.class, id);
  }

  @Test
  void 구성원_목록은_대표에게_상세를_주고_다른_구성원에게는_이름과_역할만_준다() throws Exception {
    var agent = staffIn(OfficeMembershipRole.AGENT);
    mockMvc
        .perform(get("/api/offices/current/memberships").cookie(ownerCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.items[0].role").value("OWNER"))
        .andExpect(jsonPath("$.items[0].userId").value(owner.user().getId()))
        .andExpect(jsonPath("$.items[1].billable").value(true))
        .andExpect(jsonPath("$.items[1].requestSource").value("REGISTRATION"));
    mockMvc
        .perform(get("/api/offices/current/memberships").cookie(cookieOf(agent)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[1].membershipId").value(agent.membership().getId()))
        .andExpect(jsonPath("$.items[1].displayName").exists())
        .andExpect(jsonPath("$.items[1].userId").doesNotExist())
        .andExpect(jsonPath("$.items[1].qualificationNumber").doesNotExist())
        .andExpect(jsonPath("$.items[1].billable").doesNotExist());
  }

  @Test
  void 구성원_목록은_대기와_정지_목록을_대표만_볼_수_있고_탈퇴한_구성원은_번호로_표시한다() throws Exception {
    var agent = staffIn(OfficeMembershipRole.AGENT);
    var deputy = staffIn(OfficeMembershipRole.DEPUTY);
    jdbcTemplate.update(
        "UPDATE office_membership SET status = 'SUSPENDED', suspended_at = now(),"
            + " suspension_reason = '점검' WHERE id = ?",
        deputy.membership().getId());
    mockMvc
        .perform(get("/api/offices/current/memberships?status=SUSPENDED").cookie(ownerCookie))
        .andExpect(jsonPath("$.totalElements").value(1));
    jdbcTemplate.update(
        "UPDATE app_user SET account_status = 'WITHDRAWN', withdrawn_at = now() WHERE id = ?",
        agent.user().getId());
    jdbcTemplate.update(
        "UPDATE office_membership SET withdrawn_alias_no = 1 WHERE id = ?",
        agent.membership().getId());
    mockMvc
        .perform(get("/api/offices/current/memberships").cookie(ownerCookie))
        .andExpect(jsonPath("$.items[1].displayName").value("탈퇴한 구성원 1"));
    Cookie agentCookie = cookieOf(staffIn(OfficeMembershipRole.AGENT));
    assertError(
        mockMvc.perform(get("/api/offices/current/memberships?status=PENDING").cookie(agentCookie)),
        ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(
            get("/api/offices/current/memberships?status=SUSPENDED").cookie(agentCookie)),
        ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(get("/api/offices/current/memberships?status=X").cookie(ownerCookie)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(get("/api/offices/current/memberships?size=5").cookie(ownerCookie)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(get("/api/offices/current/memberships")), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 대기_목록은_만료_시각과_초대_수락_시각을_대표에게_준다() throws Exception {
    var invitee = appUserRepository.saveAndFlush(AppUserBuilder.anAppUser().build());
    jdbcTemplate.update(
        "INSERT INTO office_membership (office_id, user_id, role, status, request_source,"
            + " invitation_expires_at) VALUES (?, ?, 'AGENT', 'PENDING', 'INVITE', now() +"
            + " interval '3 days')",
        office.getId(),
        invitee.getId());
    mockMvc
        .perform(get("/api/offices/current/memberships?status=PENDING").cookie(ownerCookie))
        .andExpect(jsonPath("$.items[0].requestSource").value("INVITE"))
        .andExpect(jsonPath("$.items[0].expiresAt").exists())
        .andExpect(jsonPath("$.items[0].billable").value(false));
  }

  @Test
  void 고용명부_등록은_사무소_버전을_올리고_한도를_막지_않는다() throws Exception {
    long before = officeVersion();
    post("/api/offices/current/employment-roster", rosterBody("김중개", "AGENT"), ownerCookie)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.legalQualification").value("AGENT"))
        .andExpect(jsonPath("$.version").value(before + 1))
        .andExpect(jsonPath("$.officeId").value(office.getId()));
    for (int i = 0; i < 6; i++) {
      post("/api/offices/current/employment-roster", rosterBody("보조" + i, "DEPUTY"), ownerCookie)
          .andExpect(status().isCreated());
    }
    assertThat(officeVersion()).isEqualTo(before + 7);
  }

  @Test
  void 고용명부_등록은_입력이_틀리면_400이고_대표가_아니면_403이다() throws Exception {
    String url = "/api/offices/current/employment-roster";
    assertError(post(url, rosterBody("", "AGENT"), ownerCookie), ErrorCode.INVALID_REQUEST);
    assertError(post(url, rosterBody("김", "OWNER"), ownerCookie), ErrorCode.INVALID_REQUEST);
    assertError(
        post(url, rosterBody("김", "AGENT").replace("MANUAL_ENTRY", "OTHER"), ownerCookie),
        ErrorCode.INVALID_REQUEST);
    assertError(post(url, "{}", ownerCookie), ErrorCode.INVALID_REQUEST);
    var other = data().approvedStaff(OfficeMembershipRole.OWNER);
    assertError(
        post(
            url,
            rosterBody("김", "AGENT")
                .replace("}", ",\"membershipId\":" + other.membership().getId() + "}"),
            ownerCookie),
        ErrorCode.INVALID_REQUEST);
    assertError(
        post(url, rosterBody("김", "AGENT"), cookieOf(staffIn(OfficeMembershipRole.AGENT))),
        ErrorCode.FORBIDDEN);
    assertError(post(url, rosterBody("김", "AGENT"), null), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 고용명부_목록은_사무소_전체_기준_한도와_초과_여부를_준다() throws Exception {
    post("/api/offices/current/employment-roster", rosterBody("김중개", "AGENT"), ownerCookie);
    for (int i = 0; i < 6; i++) {
      staffIn(OfficeMembershipRole.DEPUTY);
    }
    mockMvc
        .perform(get("/api/offices/current/employment-roster?page=0&size=25").cookie(ownerCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.quota.qualifiedCount").value(1))
        .andExpect(jsonPath("$.quota.deputyCount").value(6))
        .andExpect(jsonPath("$.quota.deputyLimit").value(5))
        .andExpect(jsonPath("$.quota.exceeded").value(true))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.totalElements").value(1));
    assertError(
        mockMvc.perform(get("/api/offices/current/employment-roster?size=3").cookie(ownerCookie)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(
            get("/api/offices/current/employment-roster")
                .cookie(cookieOf(staffIn(OfficeMembershipRole.AGENT)))),
        ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(get("/api/offices/current/employment-roster")), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 고용명부_수정은_사무소_버전으로_충돌을_검사하고_한도_초과_경고를_준다() throws Exception {
    post("/api/offices/current/employment-roster", rosterBody("김중개", "AGENT"), ownerCookie);
    for (int i = 0; i < 5; i++) {
      staffIn(OfficeMembershipRole.DEPUTY);
    }
    long rosterId = jdbcTemplate.queryForObject("SELECT id FROM employment_roster", Long.class);
    String url = "/api/offices/current/employment-roster/" + rosterId;
    long version = officeVersion();
    patch(url, "{\"employmentEndDate\":\"2026-12-31\"}", version + 9, ownerCookie)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
        .andExpect(jsonPath("$.currentVersion").value(version));
    patch(url, "{\"employmentEndDate\":\"2099-12-31\"}", version, ownerCookie)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.employmentEndDate").value("2099-12-31"))
        .andExpect(jsonPath("$.version").value(version + 1))
        .andExpect(jsonPath("$.warning").doesNotExist());
    patch(url, "{\"legalQualification\":\"DEPUTY\"}", version + 1, ownerCookie)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.warning").value("DEPUTY_QUOTA_EXCEEDED"));
  }

  @Test
  void 고용명부_수정은_허용되지_않는_필드와_잘못된_날짜면_400이고_없으면_404다() throws Exception {
    post("/api/offices/current/employment-roster", rosterBody("김중개", "AGENT"), ownerCookie);
    long rosterId = jdbcTemplate.queryForObject("SELECT id FROM employment_roster", Long.class);
    String url = "/api/offices/current/employment-roster/" + rosterId;
    long version = officeVersion();
    assertError(
        patch(url, "{\"personName\":\"x\"}", version, ownerCookie), ErrorCode.INVALID_REQUEST);
    assertError(patch(url, "{}", version, ownerCookie), ErrorCode.INVALID_REQUEST);
    assertError(
        patch(url, "{\"employmentEndDate\":\"가\"}", version, ownerCookie),
        ErrorCode.INVALID_REQUEST);
    assertError(
        patch(url, "{\"employmentEndDate\":\"2025-12-31\"}", version, ownerCookie),
        ErrorCode.INVALID_REQUEST);
    assertError(
        patch(url, "{\"legalQualification\":\"OWNER\"}", version, ownerCookie),
        ErrorCode.INVALID_REQUEST);
    assertError(
        patch(url, "{\"legalQualification\":\"AGENT\"}", null, ownerCookie),
        ErrorCode.INVALID_REQUEST);
    assertError(
        patch(
            "/api/offices/current/employment-roster/99999",
            "{\"legalQualification\":\"AGENT\"}",
            version,
            ownerCookie),
        ErrorCode.RESOURCE_NOT_FOUND);
    var other = data().approvedStaff(OfficeMembershipRole.OWNER);
    assertError(
        patch(url, "{\"legalQualification\":\"AGENT\"}", version, cookieOf(other)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        patch(
            url,
            "{\"legalQualification\":\"AGENT\"}",
            version,
            cookieOf(staffIn(OfficeMembershipRole.AGENT))),
        ErrorCode.FORBIDDEN);
    assertError(
        patch(url, "{\"legalQualification\":\"AGENT\"}", version, null), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 대표_이전은_재인증_뒤_7일_기한으로_시작하고_후보가_수락하면_심사로_넘어간다() throws Exception {
    var agent = staffIn(OfficeMembershipRole.AGENT);
    long id = transferTo(agent);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT accept_deadline_at - requested_at FROM office_owner_transfer WHERE id = ?",
                String.class,
                id))
        .startsWith("7 days");
    Cookie agentCookie = cookieOf(agent);
    post("/api/owner-transfers/" + id + "/accept", "", agentCookie)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
        .andExpect(jsonPath("$.acceptedAt").exists())
        .andExpect(jsonPath("$.toDisplayName").exists());
    mockMvc
        .perform(get("/api/offices/current/owner-transfer").cookie(agentCookie))
        .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
        .andExpect(jsonPath("$.updatedAt").isString())
        .andExpect(jsonPath("$.version").value(officeVersion()));
    mockMvc
        .perform(get("/api/owner-transfers/" + id).cookie(ownerCookie))
        .andExpect(jsonPath("$.transferId").value(id))
        .andExpect(jsonPath("$.officeId").value(office.getId()))
        .andExpect(jsonPath("$.updatedAt").isString())
        .andExpect(jsonPath("$.version").value(officeVersion()));
  }

  @Test
  void 대표_이전_시작은_재인증_후보_조건_진행_중_이전을_검사한다() throws Exception {
    var agent = staffIn(OfficeMembershipRole.AGENT);
    var deputy = staffIn(OfficeMembershipRole.DEPUTY);
    String url = "/api/offices/current/owner-transfer";
    String body = "{\"toUserId\":" + agent.user().getId() + "}";
    assertError(post(url, body, ownerCookie), ErrorCode.STEP_UP_REQUIRED);
    grantStepUp(ownerCookie);
    assertError(
        post(url, "{\"toUserId\":" + deputy.user().getId() + "}", ownerCookie),
        ErrorCode.INVALID_REQUEST);
    assertError(post(url, "{\"toUserId\":99999}", ownerCookie), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(post(url, "{}", ownerCookie), ErrorCode.INVALID_REQUEST);
    post(url, body, ownerCookie)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.updatedAt").isString())
        .andExpect(jsonPath("$.version").value(officeVersion()));
    grantStepUp(ownerCookie);
    assertError(post(url, body, ownerCookie), ErrorCode.TRANSFER_IN_PROGRESS);
    assertError(post(url, body, cookieOf(agent)), ErrorCode.FORBIDDEN);
    assertError(post(url, body, null), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 대표_이전_조회는_당사자만_하고_진행_중이_없으면_404다() throws Exception {
    Cookie third = cookieOf(staffIn(OfficeMembershipRole.AGENT));
    assertError(
        mockMvc.perform(get("/api/offices/current/owner-transfer").cookie(ownerCookie)),
        ErrorCode.RESOURCE_NOT_FOUND);
    var agent = staffIn(OfficeMembershipRole.AGENT);
    long id = transferTo(agent);
    assertError(
        mockMvc.perform(get("/api/offices/current/owner-transfer").cookie(third)),
        ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(get("/api/owner-transfers/" + id).cookie(third)), ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(get("/api/owner-transfers/99999").cookie(ownerCookie)),
        ErrorCode.RESOURCE_NOT_FOUND);
    var other = data().approvedStaff(OfficeMembershipRole.OWNER);
    assertError(
        mockMvc.perform(get("/api/owner-transfers/" + id).cookie(cookieOf(other))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(mockMvc.perform(get("/api/owner-transfers/" + id)), ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(get("/api/offices/current/owner-transfer")), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 수락과_거절은_후보만_하고_기한이_지나거나_종료됐으면_409다() throws Exception {
    var agent = staffIn(OfficeMembershipRole.AGENT);
    long id = transferTo(agent);
    Cookie agentCookie = cookieOf(agent);
    String accept = "/api/owner-transfers/" + id + "/accept";
    String reject = "/api/owner-transfers/" + id + "/reject";
    assertError(post(accept, "", ownerCookie), ErrorCode.FORBIDDEN);
    assertError(post(reject, "", ownerCookie), ErrorCode.FORBIDDEN);
    assertError(post(accept, ""), ErrorCode.UNAUTHENTICATED);
    assertError(
        post("/api/owner-transfers/99999/accept", "", agentCookie), ErrorCode.RESOURCE_NOT_FOUND);
    post(reject, "{\"reason\":\"어렵습니다\"}", agentCookie)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.rejectedAt").exists());
    assertError(post(accept, "", agentCookie), ErrorCode.INVALID_TRANSITION);
    assertError(post(reject, "", agentCookie), ErrorCode.INVALID_TRANSITION);
    long second = transferTo(agent);
    jdbcTemplate.update(
        "UPDATE office_owner_transfer SET requested_at = now() - interval '8 days',"
            + " accept_deadline_at = now() - interval '1 day' WHERE id = ?",
        second);
    assertError(
        post("/api/owner-transfers/" + second + "/accept", "", agentCookie),
        ErrorCode.INVALID_TRANSITION);
    assertThat(transferService.expireOverdue()).isEqualTo(1);
    assertThat(transferStatus(second)).isEqualTo("EXPIRED");
  }

  @Test
  void 철회는_대표만_하고_진행_중일_때만_가능하다() throws Exception {
    var agent = staffIn(OfficeMembershipRole.AGENT);
    long id = transferTo(agent);
    String url = "/api/owner-transfers/" + id + "/withdraw";
    assertError(post(url, "", cookieOf(agent)), ErrorCode.FORBIDDEN);
    assertError(post(url, ""), ErrorCode.UNAUTHENTICATED);
    assertError(
        post("/api/owner-transfers/99999/withdraw", "", ownerCookie), ErrorCode.RESOURCE_NOT_FOUND);
    post(url, "", ownerCookie)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("WITHDRAWN"))
        .andExpect(jsonPath("$.withdrawnAt").exists())
        .andExpect(jsonPath("$.updatedAt").isString())
        .andExpect(jsonPath("$.version").value(officeVersion()));
    assertError(post(url, "", ownerCookie), ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 운영자_최종_승인은_대표와_후보의_역할을_바꾸고_세션에_바로_반영한다() throws Exception {
    var agent = staffIn(OfficeMembershipRole.AGENT);
    long id = transferTo(agent);
    Cookie agentCookie = cookieOf(agent);
    post("/api/owner-transfers/" + id + "/accept", "", agentCookie);
    Cookie operator = operatorSession(createOperator("9001001", "김운영", OperatorRole.OPS));
    post("/api/ops/owner-transfers/" + id + "/approve", "", operator)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.completedAt").exists());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT role FROM office_membership WHERE id = ?",
                String.class,
                owner.membership().getId()))
        .isEqualTo("AGENT");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT role FROM office_membership WHERE id = ?",
                String.class,
                agent.membership().getId()))
        .isEqualTo("OWNER");
    mockMvc
        .perform(get("/api/office-change-requests").cookie(agentCookie))
        .andExpect(status().isOk());
    assertError(
        mockMvc.perform(get("/api/office-change-requests").cookie(ownerCookie)),
        ErrorCode.FORBIDDEN);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ops_audit_log WHERE action_type = 'OWNER_TRANSFER_APPROVED'",
                Long.class))
        .isEqualTo(1L);
  }

  @Test
  void 운영자_최종_승인은_전제가_깨지면_409이고_없으면_404_운영자가_아니면_거절한다() throws Exception {
    var agent = staffIn(OfficeMembershipRole.AGENT);
    long id = transferTo(agent);
    Cookie operator = operatorSession(createOperator("9001002", "김운영", OperatorRole.OPS));
    String url = "/api/ops/owner-transfers/" + id + "/approve";
    assertError(post(url, "", operator), ErrorCode.TRANSFER_PRECONDITION_FAILED);
    post("/api/owner-transfers/" + id + "/accept", "", cookieOf(agent));
    jdbcTemplate.update(
        "UPDATE office_membership SET status = 'REVOKED', revoked_at = now(), revoked_by_type ="
            + " 'OWNER' WHERE id = ?",
        agent.membership().getId());
    assertError(post(url, "", operator), ErrorCode.TRANSFER_PRECONDITION_FAILED);
    assertError(
        post("/api/ops/owner-transfers/99999/approve", "", operator), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(post(url, "", ownerCookie), ErrorCode.UNAUTHENTICATED);
    assertError(post(url, ""), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 후보가_탈퇴하면_진행_중인_대표_이전은_거절_처리된다() throws Exception {
    var agent = staffIn(OfficeMembershipRole.AGENT);
    long id = transferTo(agent);
    withdrawalService.releaseForWithdrawal(agent.user().getId(), Instant.now());
    assertThat(transferStatus(id)).isEqualTo("REJECTED");
  }
}
