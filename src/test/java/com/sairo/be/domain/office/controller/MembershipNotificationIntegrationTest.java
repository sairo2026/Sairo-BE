package com.sairo.be.domain.office.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.office.entity.Office;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class MembershipNotificationIntegrationTest extends AuthIntegrationTestBase {

  private static final String AGENT_BODY =
      "{\"role\":\"AGENT\",\"qualificationNumber\":\"서울-2020-0001\","
          + "\"employmentReportConfirmed\":true,\"trainingConfirmed\":true}";
  private static final String DEPUTY_BODY =
      "{\"role\":\"DEPUTY\",\"employmentReportConfirmed\":true,\"trainingConfirmed\":true}";

  @Autowired private OfficeRepository officeRepository;
  @Autowired private OfficeMembershipRepository officeMembershipRepository;

  private TestData.Staff owner;
  private TestData.Staff otherOwner;
  private Cookie ownerCookie;
  private Office office;
  private int userSequence;

  @BeforeEach
  void setUp() {
    TestData data = new TestData(appUserRepository, officeRepository, officeMembershipRepository);
    owner = data.approvedStaff(OfficeMembershipRole.OWNER);
    office = owner.office();
    otherOwner = data.approvedStaff(OfficeMembershipRole.OWNER);
    ownerCookie =
        sessions.cookie(
            sessions.createMemberSession(owner.principal(), Instant.now(), Instant.now()));
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

  private AppUser plainUser() {
    userSequence++;
    return saveEmailUser(
        "member" + userSequence + "@example.com", String.format("010-7100-%04d", userSequence));
  }

  private Cookie cookieOf(AppUser user) {
    return sessions.cookie(
        sessions.createMemberSession(
            new StaffPrincipal(user.getId(), null, null, user.getName(), null),
            Instant.now(),
            Instant.now()));
  }

  private ResultActions post(String url, String body, Cookie cookie) throws Exception {
    return mockMvc.perform(jsonPost(url, body, cookie));
  }

  private long pendingMembershipOf(Long userId) {
    return jdbcTemplate.queryForObject(
        "SELECT id FROM office_membership WHERE user_id = ? AND status = 'PENDING'",
        Long.class,
        userId);
  }

  private List<Map<String, Object>> notificationsOf(String type) {
    return jdbcTemplate.queryForList(
        "SELECT id, recipient_user_id, office_id, message, target_type, target_id FROM"
            + " notification WHERE notification_type = ? ORDER BY recipient_user_id, id",
        type);
  }

  private long recipientOf(Map<String, Object> row) {
    return ((Number) row.get("recipient_user_id")).longValue();
  }

  private long targetOf(Map<String, Object> row) {
    return ((Number) row.get("target_id")).longValue();
  }

  private long pendingEmailDeliveries(Map<String, Object> row) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM notification_delivery WHERE notification_id = ? AND channel ="
            + " 'EMAIL' AND status = 'PENDING'",
        Long.class,
        ((Number) row.get("id")).longValue());
  }

  @Test
  void 참여_요청은_해당_사무소_대표에게만_알림과_이메일_전달_대기를_만들고_신청자와_다른_사무소에는_만들지_않는다() throws Exception {
    AppUser applicant = plainUser();
    post("/api/offices/" + office.getId() + "/memberships", AGENT_BODY, cookieOf(applicant))
        .andExpect(status().isCreated());
    long membershipId = pendingMembershipOf(applicant.getId());

    List<Map<String, Object>> rows = notificationsOf("MEMBERSHIP_APPROVAL_NEEDED");
    assertThat(rows).extracting(this::recipientOf).containsExactly(owner.user().getId());
    for (Map<String, Object> row : rows) {
      assertThat(((Number) row.get("office_id")).longValue()).isEqualTo(office.getId());
      assertThat(row.get("target_type")).isEqualTo("MEMBERSHIP");
      assertThat(targetOf(row)).isEqualTo(membershipId);
      assertThat((String) row.get("message"))
          .contains(applicant.getName())
          .contains("공인중개사")
          .doesNotContain("서울-2020-0001")
          .doesNotContain(applicant.getPhone())
          .doesNotContain(applicant.getEmail());
      assertThat(pendingEmailDeliveries(row)).isEqualTo(1L);
    }
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification WHERE recipient_user_id IN (?, ?)",
                Long.class,
                applicant.getId(),
                otherOwner.user().getId()))
        .isZero();
  }

  @Test
  void 진행_중인_요청이_있어_409면_알림이_늘지_않는다() throws Exception {
    AppUser applicant = plainUser();
    Cookie cookie = cookieOf(applicant);
    String url = "/api/offices/" + office.getId() + "/memberships";
    post(url, AGENT_BODY, cookie).andExpect(status().isCreated());
    post(url, AGENT_BODY, cookie).andExpect(status().isConflict());
    assertThat(notificationsOf("MEMBERSHIP_APPROVAL_NEEDED")).hasSize(1);
  }

  @Test
  void 거절_뒤_재신청도_대표에게_새_알림을_만든다() throws Exception {
    AppUser applicant = plainUser();
    Cookie cookie = cookieOf(applicant);
    post("/api/offices/" + office.getId() + "/memberships", AGENT_BODY, cookie)
        .andExpect(status().isCreated());
    long first = pendingMembershipOf(applicant.getId());
    post("/api/memberships/" + first + "/reject", "{\"reason\":\"사유\"}", ownerCookie)
        .andExpect(status().isOk());
    post("/api/memberships/" + first + "/reapplication-start", DEPUTY_BODY, cookie)
        .andExpect(status().isOk());
    long second = pendingMembershipOf(applicant.getId());

    List<Map<String, Object>> rows = notificationsOf("MEMBERSHIP_APPROVAL_NEEDED");
    assertThat(rows).hasSize(2);
    assertThat(
            rows.stream()
                .filter(row -> targetOf(row) == second)
                .map(row -> (String) row.get("message")))
        .hasSize(1)
        .allMatch(message -> message.contains("중개보조원"));
  }

  @Test
  void 초대는_초대받은_사람에게만_알리고_수락하면_대표에게_승인_요청을_알린다() throws Exception {
    AppUser invitee = plainUser();
    post(
            "/api/offices/current/memberships/invite",
            "{\"email\":\"" + invitee.getEmail() + "\",\"role\":\"AGENT\"}",
            ownerCookie)
        .andExpect(status().isCreated());
    long membershipId = pendingMembershipOf(invitee.getId());

    List<Map<String, Object>> invitations = notificationsOf("INVITATION");
    assertThat(invitations).hasSize(1);
    Map<String, Object> invitation = invitations.get(0);
    assertThat(recipientOf(invitation)).isEqualTo(invitee.getId());
    assertThat(invitation.get("target_type")).isEqualTo("MEMBERSHIP");
    assertThat(targetOf(invitation)).isEqualTo(membershipId);
    assertThat((String) invitation.get("message")).contains(office.getName());
    assertThat(pendingEmailDeliveries(invitation)).isZero();
    assertThat(notificationsOf("MEMBERSHIP_APPROVAL_NEEDED")).isEmpty();

    post("/api/memberships/" + membershipId + "/accept-invitation", AGENT_BODY, cookieOf(invitee))
        .andExpect(status().isOk());
    assertThat(notificationsOf("MEMBERSHIP_APPROVAL_NEEDED"))
        .extracting(this::recipientOf)
        .containsExactly(owner.user().getId());
    assertThat(notificationsOf("INVITATION")).hasSize(1);
  }
}
