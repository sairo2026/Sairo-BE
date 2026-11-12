package com.sairo.be.domain.billing.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.office.entity.Office;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.GrantPrincipal;
import com.sairo.be.global.security.GrantType;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.AppUserBuilder;
import com.sairo.be.support.fixture.OfficeBuilder;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@IntegrationTest
class OfficeCloseIntegrationTest extends PaymentTestBase {

  private static final String CLOSE = "/api/offices/current/close";
  private static final String DECLINE = "{\"confirmed\":true,\"archiveChoice\":\"DECLINE\"}";
  private static final String REQUEST = "{\"confirmed\":true,\"archiveChoice\":\"REQUEST\"}";

  private String statusOf(long membershipId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM office_membership WHERE id = ?", String.class, membershipId);
  }

  private long insertMembership(long officeId, long userId, String role, String status) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO office_membership (office_id, user_id, role, status) VALUES (?, ?, ?, ?)"
            + " RETURNING id",
        Long.class,
        officeId,
        userId,
        role,
        status);
  }

  private long newUser() {
    return appUserRepository.saveAndFlush(AppUserBuilder.anAppUser().build()).getId();
  }

  @Test
  void 로그인하지_않았거나_대표가_아니면_401_403이다() throws Exception {
    assertOwnerOnly(postRequest(CLOSE, DECLINE));
  }

  @Test
  void 재인증이_없으면_401_STEP_UP_REQUIRED이고_아무것도_바꾸지_않는다() throws Exception {
    TestData.Staff owner = owner();
    assertError(
        mockMvc.perform(postRequest(CLOSE, DECLINE).apply(cookieOf(owner))),
        ErrorCode.STEP_UP_REQUIRED);
    assertThat(statusOf(owner.membership().getId())).isEqualTo("APPROVED");
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM return_grant", Long.class))
        .isZero();
  }

  @Test
  void 확인하지_않았거나_보관_선택이_없거나_허용되지_않는_필드가_있으면_400이다() throws Exception {
    TestData.Staff owner = owner();
    Cookie cookie = cookieWithStepUp(owner);
    for (String body :
        List.of(
            "{\"confirmed\":false,\"archiveChoice\":\"DECLINE\"}",
            "{\"confirmed\":true}",
            "{\"archiveChoice\":\"DECLINE\"}",
            "{\"confirmed\":true,\"archiveChoice\":\"LATER\"}",
            "{\"confirmed\":true,\"archiveChoice\":\"DECLINE\",\"officeId\":1}",
            "[]")) {
      assertError(
          mockMvc.perform(postRequest(CLOSE, body).apply(cookie)), ErrorCode.INVALID_REQUEST);
    }
    assertThat(statusOf(owner.membership().getId())).isEqualTo("APPROVED");
  }

  @Test
  void 종료하면_구독을_끝내고_잔여기간_환불을_접수하고_모든_소속을_해제하며_대표에게_30일_반환권한을_준다() throws Exception {
    Instant start = daysFromNow(-10);
    Instant end = daysFromNow(20);
    Ready ready = ready(seed -> seed.status("ACTIVE").period(start, end), true, true);
    TestData.Staff owner = ready.owner();
    long officeId = owner.office().getId();
    long paid =
        fixtures
            .invoice(officeId, ready.subscriptionId(), ready.consentId())
            .period(start, end)
            .insert();
    long unpaid =
        fixtures
            .invoice(officeId, ready.subscriptionId(), ready.consentId())
            .status("FAILED")
            .period(end, end.plus(30, ChronoUnit.DAYS))
            .insert();
    TestData.Staff agent = memberIn(owner, OfficeMembershipRole.AGENT);
    TestData.Staff suspended = memberIn(owner, OfficeMembershipRole.DEPUTY);
    jdbcTemplate.update(
        "UPDATE office_membership SET status = 'SUSPENDED', suspended_at = now(),"
            + " suspension_reason = '정지' WHERE id = ?",
        suspended.membership().getId());
    long pending = insertMembership(officeId, newUser(), "AGENT", "PENDING");
    long transfer =
        jdbcTemplate.queryForObject(
            "INSERT INTO office_owner_transfer (office_id, from_membership_id, to_membership_id,"
                + " status, requested_at, accept_deadline_at) VALUES (?, ?, ?, 'PENDING', now(),"
                + " now() + interval '7 days') RETURNING id",
            Long.class,
            officeId,
            owner.membership().getId(),
            agent.membership().getId());
    Cookie agentCookie = cookieOf(agent);
    Cookie ownerCookie = cookieWithStepUp(owner);

    var result =
        mockMvc
            .perform(postRequest(CLOSE, DECLINE).apply(ownerCookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.officeId").value(officeId))
            .andExpect(jsonPath("$.subscriptionStatus").value("ENDED"))
            .andExpect(jsonPath("$.returnGrantId").isNumber())
            .andExpect(jsonPath("$.returnExpiresAt").exists())
            .andExpect(jsonPath("$.archiveRequestStatus").value("NONE"))
            .andReturn();
    long grantId = AuthFlowSupport.json(result).get("returnGrantId").asLong();

    Map<String, Object> subscription =
        jdbcTemplate.queryForMap(
            "SELECT status, ended_at FROM subscription WHERE id = ?", ready.subscriptionId());
    assertThat(subscription.get("status")).isEqualTo("ENDED");
    assertThat(subscription.get("ended_at")).isNotNull();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM billing_invoice WHERE id = ?", String.class, unpaid))
        .isEqualTo("CANCELLED");
    Map<String, Object> refund =
        jdbcTemplate.queryForMap(
            "SELECT status, reason_code, reason_event_type, refund_amount FROM refund_request"
                + " WHERE invoice_id = ?",
            paid);
    assertThat(refund.get("status")).isEqualTo("RECEIVED");
    assertThat(refund.get("reason_code")).isEqualTo("CANCELLATION");
    assertThat(refund.get("reason_event_type")).isEqualTo("SUBSCRIPTION_CANCELLATION");
    assertThat((Long) refund.get("refund_amount")).isBetween(1L, 39_900L);
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT agreed FROM subscription_purchase_consent WHERE subscription_id = ?"
                    + " ORDER BY id",
                Boolean.class,
                ready.subscriptionId()))
        .containsExactly(true, false);

    for (long id :
        List.of(
            owner.membership().getId(),
            agent.membership().getId(),
            suspended.membership().getId())) {
      Map<String, Object> row =
          jdbcTemplate.queryForMap(
              "SELECT status, revoked_by_type, revocation_reason FROM office_membership WHERE id = ?",
              id);
      assertThat(row.get("status")).isEqualTo("REVOKED");
      assertThat(row.get("revoked_by_type")).isEqualTo("SYSTEM");
      assertThat(row.get("revocation_reason")).isEqualTo("사무소 종료");
    }
    assertThat(statusOf(pending)).isEqualTo("CANCELLED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM office_membership_status_history WHERE changed_by_type ="
                    + " 'SYSTEM' AND reason = '사무소 종료'",
                Long.class))
        .isEqualTo(4L);
    Map<String, Object> transferRow =
        jdbcTemplate.queryForMap(
            "SELECT status, withdrawn_at FROM office_owner_transfer WHERE id = ?", transfer);
    assertThat(transferRow.get("status")).isEqualTo("WITHDRAWN");
    assertThat(transferRow.get("withdrawn_at")).isNotNull();

    Map<String, Object> grant =
        jdbcTemplate.queryForMap(
            "SELECT office_id, granted_to_user_id, granted_at, expires_at, revoked_at FROM"
                + " return_grant WHERE id = ?",
            grantId);
    assertThat(grant.get("office_id")).isEqualTo(officeId);
    assertThat(grant.get("granted_to_user_id")).isEqualTo(owner.user().getId());
    assertThat(grant.get("revoked_at")).isNull();
    assertThat(((Timestamp) grant.get("expires_at")).toInstant())
        .isEqualTo(((Timestamp) grant.get("granted_at")).toInstant().plus(30, ChronoUnit.DAYS));
    assertThat(sessions.exists(sessionIdOf(agentCookie))).isFalse();
    assertThat(sessions.exists(sessionIdOf(ownerCookie))).isFalse();
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM support_inquiry", Long.class))
        .isZero();
  }

  @Test
  void 보관계약을_신청하면_결제_문의를_열고_신청됨으로_알린다() throws Exception {
    TestData.Staff owner = owner();
    mockMvc
        .perform(postRequest(CLOSE, REQUEST).apply(cookieWithStepUp(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subscriptionStatus").value("ENDED"))
        .andExpect(jsonPath("$.archiveRequestStatus").value("REQUESTED"));
    Map<String, Object> inquiry =
        jdbcTemplate.queryForMap(
            "SELECT office_id, requester_user_id, category, status, content FROM support_inquiry");
    assertThat(inquiry.get("office_id")).isEqualTo(owner.office().getId());
    assertThat(inquiry.get("requester_user_id")).isEqualTo(owner.user().getId());
    assertThat(inquiry.get("category")).isEqualTo("BILLING");
    assertThat(inquiry.get("status")).isEqualTo("OPEN");
    assertThat((String) inquiry.get("content")).contains("보관계약");
  }

  @Test
  void 미납_구독도_종료를_막지_않고_이미_끝난_구독은_그대로_두며_환불을_만들지_않는다() throws Exception {
    Ready pastDue =
        ready(
            seed ->
                seed.status("PAST_DUE_RESTRICTED")
                    .period(daysFromNow(-40), daysFromNow(-10))
                    .pastDueSince(daysFromNow(-10)),
            true,
            false);
    mockMvc
        .perform(postRequest(CLOSE, DECLINE).apply(cookieWithStepUp(pastDue.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subscriptionStatus").value("ENDED"));
    Ready ended = ready(seed -> seed.status("ENDED").endedAt(daysFromNow(-3)), false, false);
    mockMvc
        .perform(postRequest(CLOSE, DECLINE).apply(cookieWithStepUp(ended.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subscriptionStatus").value("ENDED"));
    Timestamp endedAt =
        jdbcTemplate.queryForObject(
            "SELECT ended_at FROM subscription WHERE id = ?",
            Timestamp.class,
            ended.subscriptionId());
    assertThat(endedAt.toInstant()).isBefore(Instant.now().minus(2, ChronoUnit.DAYS));
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM refund_request", Long.class))
        .isZero();
  }

  @Test
  void 종료한_대표는_탈퇴할_수_있고_탈퇴_뒤에도_반환권한으로_자료를_받는다() throws Exception {
    AppUser user = saveEmailUser("closer@example.com", "010-3333-4444");
    Office office = officeRepository.saveAndFlush(OfficeBuilder.anOffice().build());
    TestData.Staff owner = data().approvedStaffIn(office, user, OfficeMembershipRole.OWNER);
    Cookie before = login("closer@example.com", PASSWORD);
    grantStepUp(before);
    assertError(
        mockMvc.perform(AuthFlowSupport.withCsrf(delete("/api/me").cookie(before))),
        ErrorCode.LAST_OWNER);

    var result =
        mockMvc
            .perform(postRequest(CLOSE, DECLINE).apply(cookieWithStepUp(owner)))
            .andExpect(status().isOk())
            .andReturn();
    long grantId = AuthFlowSupport.json(result).get("returnGrantId").asLong();

    Cookie after = login("closer@example.com", PASSWORD);
    grantStepUp(after);
    mockMvc
        .perform(AuthFlowSupport.withCsrf(delete("/api/me").cookie(after)))
        .andExpect(status().isNoContent());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT account_status FROM app_user WHERE id = ?", String.class, user.getId()))
        .isEqualTo("WITHDRAWN");

    Cookie grantCookie =
        sessions.cookie(
            sessions.createGrantSession(
                new GrantPrincipal(user.getId(), grantId, GrantType.RETURN, office.getId()),
                Instant.now()));
    mockMvc
        .perform(getRequest("/api/return-grants/current").apply(grantCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.grantId").value(grantId))
        .andExpect(jsonPath("$.revoked").value(false));
  }

  @Test
  void 대표가_아닌_세션이면_종료_대상_사무소와_관계없이_403이고_다른_사무소는_바뀌지_않는다() throws Exception {
    TestData.Staff owner = owner();
    TestData.Staff other = owner();
    TestData.Staff agent = memberIn(owner, OfficeMembershipRole.AGENT);
    assertError(
        mockMvc.perform(postRequest(CLOSE, DECLINE).apply(cookieWithStepUp(agent))),
        ErrorCode.FORBIDDEN);
    mockMvc
        .perform(postRequest(CLOSE, DECLINE).apply(cookieWithStepUp(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.officeId").value(owner.office().getId()));
    assertThat(statusOf(other.membership().getId())).isEqualTo("APPROVED");
  }
}
