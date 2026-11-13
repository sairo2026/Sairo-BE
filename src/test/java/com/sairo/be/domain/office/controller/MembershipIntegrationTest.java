package com.sairo.be.domain.office.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPatch;
import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.AuthFlowSupport.withCsrf;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.office.entity.Office;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.domain.office.service.MembershipExpiryService;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.AppUserBuilder;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@IntegrationTest
class MembershipIntegrationTest extends AuthIntegrationTestBase {

  private static final String AGENT_BODY =
      "{\"role\":\"AGENT\",\"qualificationNumber\":\"서울-2020-0001\","
          + "\"employmentReportConfirmed\":true,\"trainingConfirmed\":true}";
  private static final String DEPUTY_BODY =
      "{\"role\":\"DEPUTY\",\"employmentReportConfirmed\":true,\"trainingConfirmed\":true}";

  @Autowired private OfficeRepository officeRepository;
  @Autowired private OfficeMembershipRepository officeMembershipRepository;
  @Autowired private MembershipExpiryService expiryService;

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

  private int userSequence;

  private AppUser plainUser() {
    userSequence++;
    return saveEmailUser(
        "member" + userSequence + "@example.com", String.format("010-7000-%04d", userSequence));
  }

  private Cookie plainCookie(AppUser user) {
    return sessions.cookie(
        sessions.createMemberSession(
            new com.sairo.be.global.security.StaffPrincipal(
                user.getId(), null, null, user.getName(), null),
            Instant.now(),
            Instant.now()));
  }

  private ResultActions post(String url, String body, Cookie cookie) throws Exception {
    return mockMvc.perform(cookie == null ? jsonPost(url, body) : jsonPost(url, body, cookie));
  }

  private ResultActions post(String url, String body) throws Exception {
    return post(url, body, null);
  }

  private long pendingRequest(AppUser user, String role) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO office_membership (office_id, user_id, role, status, request_source,"
            + " qualification_number, employment_report_confirmed, training_confirmed) VALUES"
            + " (?, ?, ?, 'PENDING', 'REQUEST', ?, true, true) RETURNING id",
        Long.class,
        office.getId(),
        user.getId(),
        role,
        role.equals("AGENT") ? "서울-1" : null);
  }

  private long invitation(AppUser user, boolean accepted, int hoursUntilExpiry) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO office_membership (office_id, user_id, role, status, request_source,"
            + " invitation_expires_at, invitation_accepted_at) VALUES (?, ?, 'AGENT', 'PENDING',"
            + " 'INVITE', now() + (? || ' hours')::interval, "
            + (accepted ? "now()" : "NULL")
            + ") RETURNING id",
        Long.class,
        office.getId(),
        user.getId(),
        String.valueOf(hoursUntilExpiry));
  }

  private String statusOf(long id) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM office_membership WHERE id = ?", String.class, id);
  }

  private long versionOf(long id) {
    return jdbcTemplate.queryForObject(
        "SELECT version FROM office_membership WHERE id = ?", Long.class, id);
  }

  private MockHttpServletRequestBuilder deleteWithBody(String url, String body, Cookie cookie) {
    return withCsrf(
        delete(url)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content(body)
            .cookie(cookie));
  }

  @Test
  void 검색은_인증_완료_사무소만_사업자번호나_이름과_지역으로_찾고_민감값은_주지_않는다() throws Exception {
    var unverified =
        officeRepository.saveAndFlush(
            com.sairo.be.support.fixture.OfficeBuilder.anOffice().name("미인증공인중개사").build());
    Cookie cookie = plainCookie(plainUser());
    String body =
        mockMvc
            .perform(
                get("/api/offices/search?businessRegistrationNumber="
                        + office.getBusinessRegistrationNumber())
                    .cookie(cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0].officeId").value(office.getId()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body)
        .doesNotContain(office.getBusinessRegistrationNumber())
        .doesNotContain("representativePhone");
    mockMvc
        .perform(get("/api/offices/search?name=공인중개사&region=종로").cookie(cookie))
        .andExpect(jsonPath("$.totalElements").value(1));
    mockMvc
        .perform(
            get("/api/offices/search?businessRegistrationNumber="
                    + unverified.getBusinessRegistrationNumber())
                .cookie(cookie))
        .andExpect(jsonPath("$.totalElements").value(0))
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  @Test
  void 종료한_사무소는_검색과_참여_요청에서_빠진다() throws Exception {
    Cookie cookie = plainCookie(plainUser());
    jdbcTemplate.update("UPDATE office SET closed_at = now() WHERE id = ?", office.getId());
    mockMvc
        .perform(
            get("/api/offices/search?businessRegistrationNumber="
                    + office.getBusinessRegistrationNumber())
                .cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
    assertError(
        post("/api/offices/" + office.getId() + "/memberships", AGENT_BODY, cookie),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 검색은_조건이_부족하면_400이고_로그인이_없으면_401이다() throws Exception {
    Cookie cookie = plainCookie(plainUser());
    assertError(
        mockMvc.perform(get("/api/offices/search?name=가").cookie(cookie)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(get("/api/offices/search").cookie(cookie)), ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(get("/api/offices/search?name=가&region=나&size=3").cookie(cookie)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(get("/api/offices/search?name=가&region=나")), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 참여_요청은_대기_소속을_만들고_본인_상태에_보인다() throws Exception {
    AppUser user = plainUser();
    Cookie cookie = plainCookie(user);
    post("/api/offices/" + office.getId() + "/memberships", AGENT_BODY, cookie)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.role").value("AGENT"))
        .andExpect(jsonPath("$.officeId").value(office.getId()))
        .andExpect(jsonPath("$.version").value(1));
    mockMvc
        .perform(get("/api/memberships/me").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.officeName").value(office.getName()))
        .andExpect(jsonPath("$.requestSource").value("REQUEST"))
        .andExpect(jsonPath("$.expiresAt").exists())
        .andExpect(jsonPath("$.resolved").doesNotExist());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM office_membership_status_history WHERE to_status = 'PENDING'"
                    + " AND changed_by_type = 'APPLICANT'",
                Long.class))
        .isEqualTo(1L);
  }

  @Test
  void 참여_요청은_역할별_입력이_틀리면_400이고_없거나_미인증_사무소는_404다() throws Exception {
    Cookie cookie = plainCookie(plainUser());
    String url = "/api/offices/" + office.getId() + "/memberships";
    assertError(
        post(
            url,
            "{\"role\":\"OWNER\",\"employmentReportConfirmed\":true,\"trainingConfirmed\":true}",
            cookie),
        ErrorCode.INVALID_REQUEST);
    assertError(
        post(
            url,
            "{\"role\":\"AGENT\",\"employmentReportConfirmed\":true,\"trainingConfirmed\":true}",
            cookie),
        ErrorCode.INVALID_REQUEST);
    assertError(
        post(
            url,
            "{\"role\":\"DEPUTY\",\"qualificationNumber\":\"1\",\"employmentReportConfirmed\":true,\"trainingConfirmed\":true}",
            cookie),
        ErrorCode.INVALID_REQUEST);
    assertError(
        post(
            url,
            "{\"role\":\"AGENT\",\"qualificationNumber\":\"1\",\"employmentReportConfirmed\":false,\"trainingConfirmed\":true}",
            cookie),
        ErrorCode.INVALID_REQUEST);
    assertError(post(url, "{}", cookie), ErrorCode.INVALID_REQUEST);
    assertError(
        post("/api/offices/99999/memberships", AGENT_BODY, cookie), ErrorCode.RESOURCE_NOT_FOUND);
    var unverified =
        officeRepository.saveAndFlush(
            com.sairo.be.support.fixture.OfficeBuilder.anOffice().build());
    assertError(
        post("/api/offices/" + unverified.getId() + "/memberships", AGENT_BODY, cookie),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(post(url, AGENT_BODY, null), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 참여_요청은_이미_진행_중이거나_승인된_소속이_있으면_409다() throws Exception {
    Cookie cookie = plainCookie(plainUser());
    String url = "/api/offices/" + office.getId() + "/memberships";
    post(url, DEPUTY_BODY, cookie).andExpect(status().isCreated());
    assertError(post(url, DEPUTY_BODY, cookie), ErrorCode.PENDING_MEMBERSHIP_EXISTS);
    assertError(
        post(url, AGENT_BODY, cookieOf(staffIn(OfficeMembershipRole.AGENT))),
        ErrorCode.PENDING_MEMBERSHIP_EXISTS);
  }

  @Test
  void 본인_상태_조회는_없으면_404이고_로그인이_없으면_401이다() throws Exception {
    assertError(
        mockMvc.perform(get("/api/memberships/me").cookie(plainCookie(plainUser()))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(mockMvc.perform(get("/api/memberships/me")), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 취소는_대기_요청을_취소하고_이력을_남기며_남의_요청은_404_종료된_요청은_409다() throws Exception {
    AppUser user = plainUser();
    Cookie cookie = plainCookie(user);
    long id = pendingRequest(user, "AGENT");
    String url = "/api/memberships/" + id + "/cancel";
    assertError(post(url, "", plainCookie(plainUser())), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(post(url, ""), ErrorCode.UNAUTHENTICATED);
    post(url, "", cookie)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
    assertError(post(url, "", cookie), ErrorCode.INVALID_TRANSITION);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM office_membership_status_history WHERE to_status = 'CANCELLED'",
                Long.class))
        .isEqualTo(1L);
  }

  @Test
  void 승인은_대기_요청을_승인하고_신청자_세션에_바로_반영한다() throws Exception {
    AppUser user = plainUser();
    Cookie applicant = plainCookie(user);
    long id = pendingRequest(user, "AGENT");
    mockMvc
        .perform(get("/api/offices/current").cookie(applicant))
        .andExpect(status().isForbidden());
    post("/api/memberships/" + id + "/approve", "", ownerCookie)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.version").value(2));
    mockMvc.perform(get("/api/offices/current").cookie(applicant)).andExpect(status().isOk());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM office_membership_status_history WHERE to_status ="
                    + " 'APPROVED' AND changed_by_type = 'OWNER' AND changed_by_membership_id = ?",
                Long.class,
                owner.membership().getId()))
        .isEqualTo(1L);
  }

  @Test
  void 승인은_중개보조원_5배_한도를_넘으면_409이고_명부의_AGENT가_있으면_통과한다() throws Exception {
    long id = pendingRequest(plainUser(), "DEPUTY");
    assertError(
        post("/api/memberships/" + id + "/approve", "", ownerCookie),
        ErrorCode.DEPUTY_QUOTA_EXCEEDED);
    jdbcTemplate.update(
        "INSERT INTO employment_roster (office_id, person_name, legal_qualification,"
            + " employment_start_date, confirmation_source) VALUES (?, '김중개', 'AGENT',"
            + " current_date, 'MANUAL_ENTRY')",
        office.getId());
    post("/api/memberships/" + id + "/approve", "", ownerCookie).andExpect(status().isOk());
  }

  @Test
  void 승인과_거절은_초대_미수락_만료_종료_요청이면_409이고_남의_사무소나_없으면_404다() throws Exception {
    long unaccepted = invitation(plainUser(), false, 48);
    long expired = invitation(plainUser(), true, -1);
    long done = pendingRequest(plainUser(), "AGENT");
    jdbcTemplate.update(
        "UPDATE office_membership SET status = 'CANCELLED', cancelled_at = now(), resolved_at = now() WHERE id = ?",
        done);
    for (long id : new long[] {unaccepted, expired, done}) {
      assertError(
          post("/api/memberships/" + id + "/approve", "", ownerCookie),
          ErrorCode.INVALID_TRANSITION);
      assertError(
          post("/api/memberships/" + id + "/reject", "{\"reason\":\"사유\"}", ownerCookie),
          ErrorCode.INVALID_TRANSITION);
    }
    assertError(
        post("/api/memberships/99999/approve", "", ownerCookie), ErrorCode.RESOURCE_NOT_FOUND);
    var other = data().approvedStaff(OfficeMembershipRole.OWNER);
    long foreign = pendingRequest(plainUser(), "AGENT");
    assertError(
        post("/api/memberships/" + foreign + "/approve", "", cookieOf(other)),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 승인과_거절은_대표만_하고_거절은_사유가_필요하다() throws Exception {
    long id = pendingRequest(plainUser(), "AGENT");
    Cookie agent = cookieOf(staffIn(OfficeMembershipRole.AGENT));
    assertError(post("/api/memberships/" + id + "/approve", "", agent), ErrorCode.FORBIDDEN);
    assertError(
        post("/api/memberships/" + id + "/reject", "{\"reason\":\"x\"}", agent),
        ErrorCode.FORBIDDEN);
    assertError(post("/api/memberships/" + id + "/approve", ""), ErrorCode.UNAUTHENTICATED);
    assertError(
        post("/api/memberships/" + id + "/reject", "{\"reason\":\" \"}", ownerCookie),
        ErrorCode.INVALID_REQUEST);
    post("/api/memberships/" + id + "/reject", "{\"reason\":\"자격 확인 불가\"}", ownerCookie)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT rejection_reason FROM office_membership WHERE id = ?", String.class, id))
        .isEqualTo("자격 확인 불가");
  }

  @Test
  void 동시에_두_번_승인하면_하나만_성공한다() throws Exception {
    long id = pendingRequest(plainUser(), "AGENT");
    var pool = Executors.newFixedThreadPool(2);
    try {
      var a =
          pool.submit(
              () ->
                  post("/api/memberships/" + id + "/approve", "", ownerCookie)
                      .andReturn()
                      .getResponse()
                      .getStatus());
      var b =
          pool.submit(
              () ->
                  post("/api/memberships/" + id + "/approve", "", ownerCookie)
                      .andReturn()
                      .getResponse()
                      .getStatus());
      assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 409);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void 재신청은_거절된_행을_해결됨으로_바꾸고_새_대기_요청을_만든다() throws Exception {
    AppUser user = plainUser();
    Cookie cookie = plainCookie(user);
    long id = pendingRequest(user, "AGENT");
    post("/api/memberships/" + id + "/reject", "{\"reason\":\"사유\"}", ownerCookie);
    mockMvc
        .perform(get("/api/memberships/me").cookie(cookie))
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.rejectionReason").value("사유"))
        .andExpect(jsonPath("$.resolved").value(false));
    String url = "/api/memberships/" + id + "/reapplication-start";
    post(url, DEPUTY_BODY, cookie)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.role").value("DEPUTY"));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT resolved_at IS NOT NULL FROM office_membership WHERE id = ?",
                Boolean.class,
                id))
        .isTrue();
    assertError(post(url, DEPUTY_BODY, cookie), ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 재신청은_거절이_아니면_409이고_남의_행은_404이고_입력이_틀리면_400이다() throws Exception {
    AppUser user = plainUser();
    Cookie cookie = plainCookie(user);
    long pending = pendingRequest(user, "AGENT");
    assertError(
        post("/api/memberships/" + pending + "/reapplication-start", AGENT_BODY, cookie),
        ErrorCode.INVALID_TRANSITION);
    post("/api/memberships/" + pending + "/reject", "{\"reason\":\"사유\"}", ownerCookie);
    assertError(
        post(
            "/api/memberships/" + pending + "/reapplication-start",
            AGENT_BODY,
            plainCookie(plainUser())),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        post("/api/memberships/" + pending + "/reapplication-start", "{}", cookie),
        ErrorCode.INVALID_REQUEST);
    assertError(
        post("/api/memberships/" + pending + "/reapplication-start", AGENT_BODY),
        ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 초대는_대기_소속과_안내_메일을_만들고_수락_뒤_대표가_승인한다() throws Exception {
    AppUser target = plainUser();
    Cookie targetCookie = plainCookie(target);
    String invite = "{\"email\":\"" + target.getEmail() + "\",\"role\":\"AGENT\"}";
    var result =
        post("/api/offices/current/memberships/invite", invite, ownerCookie)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.expiresAt").exists())
            .andReturn();
    long id = com.sairo.be.support.AuthFlowSupport.json(result).get("membershipId").asLong();
    assertThat(mailSender.sent()).anyMatch(mail -> mail.to().equals(target.getEmail()));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT invitation_expires_at - created_at FROM office_membership WHERE id = ?",
                String.class,
                id))
        .startsWith("7 days");
    assertError(
        post("/api/memberships/" + id + "/approve", "", ownerCookie), ErrorCode.INVALID_TRANSITION);
    post("/api/memberships/" + id + "/accept-invitation", AGENT_BODY, targetCookie)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.invitationAcceptedAt").exists());
    post("/api/memberships/" + id + "/approve", "", ownerCookie).andExpect(status().isOk());
    assertThat(statusOf(id)).isEqualTo("APPROVED");
  }

  @Test
  void 초대는_없는_계정이면_404_이미_소속이_있으면_409_대표_역할이면_400_권한이_없으면_403이다() throws Exception {
    AppUser target = plainUser();
    String url = "/api/offices/current/memberships/invite";
    assertError(
        post(url, "{\"email\":\"none@example.com\",\"role\":\"AGENT\"}", ownerCookie),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        post(url, "{\"email\":\"" + target.getEmail() + "\",\"role\":\"OWNER\"}", ownerCookie),
        ErrorCode.INVALID_REQUEST);
    assertError(
        post(url, "{\"email\":\"bad\",\"role\":\"AGENT\"}", ownerCookie),
        ErrorCode.INVALID_REQUEST);
    pendingRequest(target, "AGENT");
    assertError(
        post(url, "{\"email\":\"" + target.getEmail() + "\",\"role\":\"AGENT\"}", ownerCookie),
        ErrorCode.PENDING_MEMBERSHIP_EXISTS);
    Cookie agent = cookieOf(staffIn(OfficeMembershipRole.AGENT));
    assertError(
        post(url, "{\"email\":\"a@b.com\",\"role\":\"AGENT\"}", agent), ErrorCode.FORBIDDEN);
    assertError(
        post(url, "{\"email\":\"a@b.com\",\"role\":\"AGENT\"}", null), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 초대_수락은_초대가_아니거나_만료면_409_입력이_틀리면_400_남의_행은_404다() throws Exception {
    AppUser user = plainUser();
    Cookie cookie = plainCookie(user);
    long request = pendingRequest(user, "AGENT");
    assertError(
        post("/api/memberships/" + request + "/accept-invitation", AGENT_BODY, cookie),
        ErrorCode.INVALID_TRANSITION);
    AppUser other = plainUser();
    long expired = invitation(other, false, -1);
    assertError(
        post("/api/memberships/" + expired + "/accept-invitation", AGENT_BODY, plainCookie(other)),
        ErrorCode.INVALID_TRANSITION);
    AppUser third = plainUser();
    long live = invitation(third, false, 48);
    assertError(
        post("/api/memberships/" + live + "/accept-invitation", "{}", plainCookie(third)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        post("/api/memberships/" + live + "/accept-invitation", AGENT_BODY, cookie),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        post("/api/memberships/" + live + "/accept-invitation", AGENT_BODY),
        ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 초대_취소와_재발송은_대표가_대기_초대에만_할_수_있다() throws Exception {
    AppUser target = plainUser();
    long id = invitation(target, false, 1);
    mailSender.clear();
    post("/api/memberships/" + id + "/invitation/resend", "", ownerCookie)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.expiresAt").exists());
    assertThat(mailSender.sent()).hasSize(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT invitation_expires_at > now() + interval '6 days' FROM office_membership WHERE id = ?",
                Boolean.class,
                id))
        .isTrue();
    post("/api/memberships/" + id + "/invitation/cancel", "", ownerCookie)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
    assertError(
        post("/api/memberships/" + id + "/invitation/cancel", "", ownerCookie),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        post("/api/memberships/" + id + "/invitation/resend", "", ownerCookie),
        ErrorCode.INVALID_TRANSITION);
    long request = pendingRequest(plainUser(), "AGENT");
    assertError(
        post("/api/memberships/" + request + "/invitation/cancel", "", ownerCookie),
        ErrorCode.INVALID_TRANSITION);
    var other = data().approvedStaff(OfficeMembershipRole.OWNER);
    long mine = invitation(plainUser(), false, 5);
    assertError(
        post("/api/memberships/" + mine + "/invitation/cancel", "", cookieOf(other)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        post(
            "/api/memberships/" + mine + "/invitation/resend",
            "",
            cookieOf(staffIn(OfficeMembershipRole.AGENT))),
        ErrorCode.FORBIDDEN);
    assertError(
        post("/api/memberships/" + mine + "/invitation/resend", ""), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 역할_변경은_재인증과_버전을_요구하고_세션에_바로_반영한다() throws Exception {
    var agent = staffIn(OfficeMembershipRole.AGENT);
    Cookie agentCookie = cookieOf(agent);
    long id = agent.membership().getId();
    String url = "/api/memberships/" + id + "/role";
    assertError(roleRequest(url, "DEPUTY", versionOf(id), ownerCookie), ErrorCode.STEP_UP_REQUIRED);
    grantStepUp(ownerCookie);
    assertError(
        roleRequest(url, "DEPUTY", versionOf(id) + 5, ownerCookie), ErrorCode.VERSION_CONFLICT);
    roleRequest(url, "DEPUTY", versionOf(id), ownerCookie)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("DEPUTY"))
        .andExpect(jsonPath("$.version").value(2));
    assertError(roleRequest(url, "AGENT", versionOf(id), ownerCookie), ErrorCode.STEP_UP_REQUIRED);
    assertThat(agentCookie).isNotNull();
  }

  private ResultActions roleRequest(String url, String role, long version, Cookie cookie)
      throws Exception {
    return mockMvc.perform(
        jsonPatch(url, "{\"role\":\"" + role + "\"}", cookie)
            .header("If-Match", String.valueOf(version)));
  }

  @Test
  void 역할_변경은_대표_지정과_대표_대상이면_400이고_권한_범위를_지킨다() throws Exception {
    var agent = staffIn(OfficeMembershipRole.AGENT);
    grantStepUp(ownerCookie);
    long id = agent.membership().getId();
    assertError(
        roleRequest("/api/memberships/" + id + "/role", "OWNER", versionOf(id), ownerCookie),
        ErrorCode.INVALID_REQUEST);
    long ownerId = owner.membership().getId();
    assertError(
        roleRequest(
            "/api/memberships/" + ownerId + "/role", "AGENT", versionOf(ownerId), ownerCookie),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(
            jsonPatch("/api/memberships/" + id + "/role", "{\"role\":\"AGENT\"}", ownerCookie)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        roleRequest("/api/memberships/99999/role", "AGENT", 1, ownerCookie),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        roleRequest("/api/memberships/" + id + "/role", "DEPUTY", 1, cookieOf(agent)),
        ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(jsonPatch("/api/memberships/" + id + "/role", "{\"role\":\"AGENT\"}")),
        ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 정지는_세션을_끊고_해제하면_다시_승인_상태가_된다() throws Exception {
    var agent = staffIn(OfficeMembershipRole.AGENT);
    Cookie agentCookie = cookieOf(agent);
    long id = agent.membership().getId();
    mockMvc.perform(get("/api/offices/current").cookie(agentCookie)).andExpect(status().isOk());
    post("/api/memberships/" + id + "/suspend", "{\"reason\":\"점검\"}", ownerCookie)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUSPENDED"));
    assertError(
        mockMvc.perform(get("/api/offices/current").cookie(agentCookie)),
        ErrorCode.UNAUTHENTICATED);
    post("/api/memberships/" + id + "/reinstate", "", ownerCookie)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT suspended_at IS NULL FROM office_membership WHERE id = ?",
                Boolean.class,
                id))
        .isTrue();
  }

  @Test
  void 정지와_해제는_상태가_맞지_않으면_409_유일한_대표는_LAST_OWNER_사유_누락은_400이다() throws Exception {
    var agent = staffIn(OfficeMembershipRole.AGENT);
    long id = agent.membership().getId();
    assertError(
        post("/api/memberships/" + id + "/reinstate", "", ownerCookie),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        post("/api/memberships/" + id + "/suspend", "{}", ownerCookie), ErrorCode.INVALID_REQUEST);
    post("/api/memberships/" + id + "/suspend", "{\"reason\":\"점검\"}", ownerCookie)
        .andExpect(status().isOk());
    assertError(
        post("/api/memberships/" + id + "/suspend", "{\"reason\":\"점검\"}", ownerCookie),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        post(
            "/api/memberships/" + owner.membership().getId() + "/suspend",
            "{\"reason\":\"x\"}",
            ownerCookie),
        ErrorCode.LAST_OWNER);
    assertError(
        post("/api/memberships/99999/suspend", "{\"reason\":\"x\"}", ownerCookie),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        post(
            "/api/memberships/" + id + "/suspend",
            "{\"reason\":\"x\"}",
            cookieOf(staffIn(OfficeMembershipRole.AGENT))),
        ErrorCode.FORBIDDEN);
    assertError(post("/api/memberships/" + id + "/reinstate", ""), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 회수는_재인증_뒤_사유와_함께_소속을_회수하고_204이며_세션을_끊는다() throws Exception {
    var agent = staffIn(OfficeMembershipRole.AGENT);
    Cookie agentCookie = cookieOf(agent);
    long id = agent.membership().getId();
    String url = "/api/memberships/" + id;
    assertError(
        mockMvc.perform(deleteWithBody(url, "{\"reason\":\"퇴사\"}", ownerCookie)),
        ErrorCode.STEP_UP_REQUIRED);
    grantStepUp(ownerCookie);
    mockMvc
        .perform(deleteWithBody(url, "{\"reason\":\"퇴사\"}", ownerCookie))
        .andExpect(status().isNoContent());
    assertThat(
            jdbcTemplate.queryForMap(
                "SELECT status, revoked_by_type, revocation_reason FROM office_membership WHERE id = ?",
                id))
        .containsEntry("status", "REVOKED")
        .containsEntry("revoked_by_type", "OWNER")
        .containsEntry("revocation_reason", "퇴사");
    assertError(
        mockMvc.perform(get("/api/offices/current").cookie(agentCookie)),
        ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 회수는_유일한_대표면_LAST_OWNER_사유가_없으면_400_상태가_맞지_않으면_409다() throws Exception {
    grantStepUp(ownerCookie);
    assertError(
        mockMvc.perform(
            deleteWithBody(
                "/api/memberships/" + owner.membership().getId(),
                "{\"reason\":\"x\"}",
                ownerCookie)),
        ErrorCode.LAST_OWNER);
    var agent = staffIn(OfficeMembershipRole.AGENT);
    String url = "/api/memberships/" + agent.membership().getId();
    assertError(mockMvc.perform(deleteWithBody(url, "{}", ownerCookie)), ErrorCode.INVALID_REQUEST);
    mockMvc
        .perform(deleteWithBody(url, "{\"reason\":\"퇴사\"}", ownerCookie))
        .andExpect(status().isNoContent());
    grantStepUp(ownerCookie);
    assertError(
        mockMvc.perform(deleteWithBody(url, "{\"reason\":\"퇴사\"}", ownerCookie)),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        mockMvc.perform(
            deleteWithBody("/api/memberships/99999", "{\"reason\":\"x\"}", ownerCookie)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(
            deleteWithBody(
                url, "{\"reason\":\"x\"}", cookieOf(staffIn(OfficeMembershipRole.AGENT)))),
        ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(withCsrf(delete(url))), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 나가기는_본인_소속을_회수하고_계정은_유지하며_유일한_대표는_409다() throws Exception {
    var agent = staffIn(OfficeMembershipRole.AGENT);
    Cookie agentCookie = cookieOf(agent);
    post("/api/memberships/me/leave", "", agentCookie).andExpect(status().isNoContent());
    assertThat(
            jdbcTemplate.queryForMap(
                "SELECT status, revoked_by_type FROM office_membership WHERE id = ?",
                agent.membership().getId()))
        .containsEntry("status", "REVOKED")
        .containsEntry("revoked_by_type", "SELF");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT account_status FROM app_user WHERE id = ?",
                String.class,
                agent.user().getId()))
        .isEqualTo("ACTIVE");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM office_membership_status_history WHERE to_status = 'REVOKED'"
                    + " AND changed_by_type = 'APPLICANT'",
                Long.class))
        .isEqualTo(1L);
    assertError(post("/api/memberships/me/leave", "", ownerCookie), ErrorCode.LAST_OWNER);
    assertError(
        post("/api/memberships/me/leave", "", plainCookie(plainUser())), ErrorCode.FORBIDDEN);
    assertError(post("/api/memberships/me/leave", ""), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 구성원_상세는_대표만_보고_인계될_업무_건수를_주며_다른_사무소는_404다() throws Exception {
    var agent = staffIn(OfficeMembershipRole.AGENT);
    long id = agent.membership().getId();
    mockMvc
        .perform(get("/api/memberships/" + id).cookie(ownerCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.membershipId").value(id))
        .andExpect(jsonPath("$.userId").value(agent.user().getId()))
        .andExpect(jsonPath("$.billable").value(true))
        .andExpect(jsonPath("$.assignedCounts.customers").value(0))
        .andExpect(jsonPath("$.assignedCounts.properties").value(0));
    var other = data().approvedStaff(OfficeMembershipRole.OWNER);
    assertError(
        mockMvc.perform(get("/api/memberships/" + id).cookie(cookieOf(other))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(
            get("/api/memberships/" + id).cookie(cookieOf(staffIn(OfficeMembershipRole.AGENT)))),
        ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(get("/api/memberships/" + id)), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 기한_정리는_30일_지난_요청과_7일_지난_초대만_만료시키고_이력을_남긴다() throws Exception {
    long stale = pendingRequest(plainUser(), "AGENT");
    jdbcTemplate.update(
        "UPDATE office_membership SET created_at = now() - interval '31 days' WHERE id = ?", stale);
    long fresh = pendingRequest(plainUser(), "AGENT");
    long overdueInvite = invitation(plainUser(), false, -1);
    long liveInvite = invitation(plainUser(), false, 5);
    assertThat(expiryService.expireOverdue()).isEqualTo(2);
    assertThat(statusOf(stale)).isEqualTo("EXPIRED");
    assertThat(statusOf(overdueInvite)).isEqualTo("EXPIRED");
    assertThat(statusOf(fresh)).isEqualTo("PENDING");
    assertThat(statusOf(liveInvite)).isEqualTo("PENDING");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM office_membership_status_history WHERE to_status = 'EXPIRED'"
                    + " AND changed_by_type = 'SYSTEM'",
                Long.class))
        .isEqualTo(2L);
  }
}
