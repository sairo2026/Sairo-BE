package com.sairo.be.domain.ops.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.security.PrincipalSessions;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsConsoleTestBase;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.Test;

@IntegrationTest
class OpsConsoleIntegrationTest extends OpsConsoleTestBase {

  private static final String[] GET_PATHS = {
    "/api/ops/home",
    "/api/ops/offices",
    "/api/ops/offices/1",
    "/api/ops/members",
    "/api/ops/members/1",
    "/api/ops/operators",
    "/api/ops/audit-log",
    "/api/ops/operator-invitations",
  };

  private static final String[] POST_PATHS = {
    "/api/ops/members/1/emergency-suspend",
    "/api/ops/operators/1/roles",
    "/api/ops/operators/1/disable",
    "/api/ops/operators/1/enable",
    "/api/ops/operator-invitations",
    "/api/ops/operator-invitations/1/cancel",
  };

  private long sequence;

  private long pendingRegistration(int daysAgo) {
    sequence++;
    long applicant =
        appUserRepository
            .saveAndFlush(
                com.sairo.be.support.fixture.AppUserBuilder.anAppUser().name("신청자").build())
            .getId();
    return jdbcTemplate.queryForObject(
        "INSERT INTO office_registration (applicant_user_id, status, entity_type,"
            + " registration_authority, real_estate_license_number, business_registration_number,"
            + " requested_office_name, requested_representative_name,"
            + " requested_representative_phone, requested_address,"
            + " applicant_registration_relation, created_at) VALUES (?, 'PENDING_REVIEW',"
            + " 'INDIVIDUAL', '서울특별시 종로구', ?, ?, '신청사무소', '홍대표', '02-123-4567',"
            + " '서울 종로구 1', 'REPRESENTATIVE', now() - (? || ' days')::interval) RETURNING id",
        Long.class,
        applicant,
        "LIC-H-" + sequence,
        String.format("%010d", 4_000_000_000L + sequence),
        String.valueOf(daysAgo));
  }

  @Test
  void 새_엔드포인트는_세션이_없으면_401이고_역할이_없으면_403이다() throws Exception {
    for (String path : GET_PATHS) {
      assertError(mockMvc.perform(get(path)), ErrorCode.UNAUTHENTICATED);
    }
    for (String path : POST_PATHS) {
      assertError(post(path, "{}", null), ErrorCode.UNAUTHENTICATED);
    }
    Cookie exec = sessionOf(operator("경영조회", OperatorRole.EXEC_VIEW));
    for (String path :
        new String[] {
          "/api/ops/offices/1",
          "/api/ops/members",
          "/api/ops/members/1",
          "/api/ops/operators",
          "/api/ops/audit-log",
          "/api/ops/operator-invitations"
        }) {
      assertError(mockMvc.perform(get(path).cookie(exec)), ErrorCode.FORBIDDEN);
    }
    for (String path :
        new String[] {
          "/api/ops/members/1/emergency-suspend",
          "/api/ops/operators/1/roles",
          "/api/ops/operators/1/disable",
          "/api/ops/operators/1/enable",
          "/api/ops/operator-invitations",
          "/api/ops/operator-invitations/1/cancel",
        }) {
      assertError(post(path, "{}", exec), ErrorCode.FORBIDDEN);
    }
    TestData.Staff owner = staff(OfficeMembershipRole.OWNER);
    Cookie member = memberSession(owner);
    assertError(mockMvc.perform(get("/api/ops/home").cookie(member)), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 운영_홈은_큐_건수를_주고_경영지표는_경영조회_역할에만_담는다() throws Exception {
    pendingRegistration(10);
    pendingRegistration(0);
    TestData.Staff owner = staff(OfficeMembershipRole.OWNER);
    insertInquiry(owner.office().getId(), owner.user().getId());
    jdbcTemplate.update(
        "INSERT INTO subscription (office_id, plan, status) VALUES (?, 'SOLO', 'ACTIVE')",
        owner.office().getId());
    jdbcTemplate.update(
        "INSERT INTO incident_log (title, affected_services, status) VALUES ('장애', 'API',"
            + " 'DETECTED')");
    jdbcTemplate.update(
        "INSERT INTO incident_log (title, affected_services, status) VALUES ('끝난 장애', 'API',"
            + " 'CLOSED')");

    mockMvc
        .perform(get("/api/ops/home").cookie(sessionOf(operator("심사", OperatorRole.REVIEW))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pendingReviewCount").value(2))
        .andExpect(jsonPath("$.overdueReviewCount").value(1))
        .andExpect(jsonPath("$.unassignedInquiryCount").value(1))
        .andExpect(jsonPath("$.paymentPendingCount").value(0))
        .andExpect(jsonPath("$.refundFailedCount").value(0))
        .andExpect(jsonPath("$.migrationReviewPendingCount").value(0))
        .andExpect(jsonPath("$.privacyDeadlineApproachingCount").value(0))
        .andExpect(jsonPath("$.deletionFailedCount").value(0))
        .andExpect(jsonPath("$.activeIncidentCount").value(1))
        .andExpect(jsonPath("$.execSummary").doesNotExist());

    mockMvc
        .perform(get("/api/ops/home").cookie(sessionOf(operator("경영", OperatorRole.EXEC_VIEW))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.execSummary.activeOfficeCount").value(1))
        .andExpect(jsonPath("$.execSummary.trialOfficeCount").value(0))
        .andExpect(jsonPath("$.execSummary.netCollectedAmount").value(0))
        .andExpect(jsonPath("$.execSummary.asOf").exists());
  }

  @Test
  void 고객사_목록은_검색어와_상태로_거르고_형식이_틀리면_400이다() throws Exception {
    TestData.Staff first = staff(OfficeMembershipRole.OWNER);
    TestData.Staff second = staff(OfficeMembershipRole.OWNER);
    jdbcTemplate.update("UPDATE office SET name = '사이로중개' WHERE id = ?", second.office().getId());
    jdbcTemplate.update(
        "INSERT INTO subscription (office_id, plan, status) VALUES (?, 'SOLO', 'ACTIVE')",
        first.office().getId());
    Cookie exec = sessionOf(operator("경영", OperatorRole.EXEC_VIEW));

    mockMvc
        .perform(get("/api/ops/offices").cookie(exec))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.items[0].officeId").value(second.office().getId()))
        .andExpect(jsonPath("$.items[0].registrationStatus").value("APPROVED"))
        .andExpect(jsonPath("$.items[0].subscriptionStatus").isEmpty())
        .andExpect(jsonPath("$.items[0].approvedMembershipCount").value(1))
        .andExpect(jsonPath("$.items[0].version").value(1));
    mockMvc
        .perform(get("/api/ops/offices?keyword=사이로").cookie(exec))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].name").value("사이로중개"));
    mockMvc
        .perform(get("/api/ops/offices?subscriptionStatus=ACTIVE").cookie(exec))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].officeId").value(first.office().getId()));
    mockMvc
        .perform(get("/api/ops/offices?registrationStatus=REVOKED").cookie(exec))
        .andExpect(jsonPath("$.totalElements").value(0));
    assertError(
        mockMvc.perform(get("/api/ops/offices?registrationStatus=DRAFT").cookie(exec)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(get("/api/ops/offices?size=7").cookie(exec)), ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 고객사_상세는_구성원과_고용명부와_구독과_문의_수를_주고_없으면_404다() throws Exception {
    TestData.Staff owner = staff(OfficeMembershipRole.OWNER);
    long officeId = owner.office().getId();
    jdbcTemplate.update(
        "INSERT INTO employment_roster (office_id, person_name, legal_qualification,"
            + " employment_start_date, confirmation_source) VALUES (?, '김중개', 'AGENT',"
            + " current_date - 10, 'MANUAL_ENTRY'), (?, '이보조', 'DEPUTY', current_date - 10,"
            + " 'MANUAL_ENTRY'), (?, '박퇴사', 'AGENT', current_date - 100, 'MANUAL_ENTRY')",
        officeId,
        officeId,
        officeId);
    jdbcTemplate.update(
        "UPDATE employment_roster SET employment_end_date = current_date - 50 WHERE person_name ="
            + " '박퇴사'");
    jdbcTemplate.update(
        "INSERT INTO subscription (office_id, plan, status, current_period_end) VALUES (?, 'TEAM',"
            + " 'ACTIVE', now() + interval '10 days')",
        officeId);
    insertInquiry(officeId, owner.user().getId());
    Cookie support = sessionOf(operator("지원", OperatorRole.SUPPORT));

    mockMvc
        .perform(get("/api/ops/offices/" + officeId).cookie(support))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.officeId").value(officeId))
        .andExpect(jsonPath("$.representativeName").value("홍대표"))
        .andExpect(jsonPath("$.phone").value("010-0000-0000"))
        .andExpect(jsonPath("$.registrationStatus").value("APPROVED"))
        .andExpect(jsonPath("$.registrationHistory", hasSize(0)))
        .andExpect(jsonPath("$.members", hasSize(1)))
        .andExpect(jsonPath("$.members[0].role").value("OWNER"))
        .andExpect(jsonPath("$.members[0].status").value("APPROVED"))
        .andExpect(jsonPath("$.employmentRoster.agentCount").value(1))
        .andExpect(jsonPath("$.employmentRoster.deputyCount").value(1))
        .andExpect(jsonPath("$.subscription.plan").value("TEAM"))
        .andExpect(jsonPath("$.subscription.currentPeriodEnd").exists())
        .andExpect(jsonPath("$.openInquiryCount").value(1))
        .andExpect(jsonPath("$.returnGrant").isEmpty())
        .andExpect(jsonPath("$.archiveGrant").isEmpty())
        .andExpect(jsonPath("$.version").value(1));
    assertError(
        mockMvc.perform(get("/api/ops/offices/999999").cookie(support)),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 회원_목록과_상세는_연락처를_주지_않고_세션과_보안_사건을_준다() throws Exception {
    TestData.Staff owner = staff(OfficeMembershipRole.OWNER);
    jdbcTemplate.update("UPDATE app_user SET name = '김검색' WHERE id = ?", owner.user().getId());
    staff(OfficeMembershipRole.AGENT);
    String sessionId =
        sessions.createMemberSession(owner.principal(), Instant.now(), Instant.now());
    Cookie support = sessionOf(operator("지원", OperatorRole.SUPPORT));

    mockMvc
        .perform(get("/api/ops/members?keyword=김검색").cookie(support))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].userId").value(owner.user().getId()))
        .andExpect(jsonPath("$.items[0].displayName").value("김검색"))
        .andExpect(jsonPath("$.items[0].accountStatus").value("ACTIVE"))
        .andExpect(jsonPath("$.items[0].officeName").value(owner.office().getName()));
    mockMvc
        .perform(get("/api/ops/members?accountStatus=WITHDRAWN").cookie(support))
        .andExpect(jsonPath("$.totalElements").value(0));
    assertError(
        mockMvc.perform(get("/api/ops/members?accountStatus=X").cookie(support)),
        ErrorCode.INVALID_REQUEST);

    mockMvc
        .perform(get("/api/ops/members/" + owner.user().getId()).cookie(support))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("김검색"))
        .andExpect(jsonPath("$.currentMembership.officeId").value(owner.office().getId()))
        .andExpect(jsonPath("$.currentMembership.role").value("OWNER"))
        .andExpect(jsonPath("$.loginMethods[0]").value("KAKAO"))
        .andExpect(jsonPath("$.sessions", hasSize(1)))
        .andExpect(jsonPath("$.sessions[0].sessionId").value(PrincipalSessions.opaqueId(sessionId)))
        .andExpect(jsonPath("$.sessions[0].deviceSummary").exists())
        .andExpect(jsonPath("$.securityEvents", hasSize(0)))
        .andExpect(jsonPath("$.email").doesNotExist())
        .andExpect(jsonPath("$.phone").doesNotExist());
    assertError(
        mockMvc.perform(get("/api/ops/members/999999").cookie(support)),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 긴급_중단은_세션을_끝내고_기록과_대표_알림과_감사_로그를_남긴다() throws Exception {
    TestData.Staff owner = staff(OfficeMembershipRole.OWNER);
    long userId = owner.user().getId();
    String first = sessions.createMemberSession(owner.principal(), Instant.now(), Instant.now());
    String second = sessions.createMemberSession(owner.principal(), Instant.now(), Instant.now());
    Operator admin = operator("김보안", OperatorRole.SECURITY_ADMIN);
    Cookie adminSession = sessionOf(admin);
    String path = "/api/ops/members/" + userId + "/emergency-suspend";
    String review = "\"reviewAt\":\"" + Instant.now().plusSeconds(86_400) + "\"";

    assertError(
        post(
            path,
            "{\"reason\":\"탈취 의심\",\"scope\":\"SPECIFIC_SESSION\"," + review + "}",
            adminSession),
        ErrorCode.INVALID_REQUEST);
    assertError(
        post(
            path,
            "{\"reason\":\"탈취 의심\",\"scope\":\"ALL_SESSIONS\",\"sessionId\":\"s-1\","
                + review
                + "}",
            adminSession),
        ErrorCode.INVALID_REQUEST);
    assertError(
        post(
            path,
            "{\"reason\":\"탈취 의심\",\"scope\":\"ALL_SESSIONS\",\"reviewAt\":\"2020-01-01T00:00:00Z\"}",
            adminSession),
        ErrorCode.INVALID_REQUEST);
    assertError(
        post(
            path,
            "{\"reason\":\"탈취 의심\",\"scope\":\"SPECIFIC_SESSION\",\"sessionId\":\"s-unknown\","
                + review
                + "}",
            adminSession),
        ErrorCode.RESOURCE_NOT_FOUND);

    post(
            path,
            "{\"reason\":\"탈취 의심\",\"scope\":\"SPECIFIC_SESSION\",\"sessionId\":\""
                + PrincipalSessions.opaqueId(first)
                + "\","
                + review
                + "}",
            adminSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(userId))
        .andExpect(jsonPath("$.scope").value("SPECIFIC_SESSION"))
        .andExpect(jsonPath("$.suspendedAt").exists());
    assertThat(sessions.exists(first)).isFalse();
    assertThat(sessions.exists(second)).isTrue();

    post(path, "{\"reason\":\"계정 탈취 확인\",\"scope\":\"ALL_SESSIONS\"," + review + "}", adminSession)
        .andExpect(status().isOk());
    assertThat(sessions.exists(second)).isFalse();
    assertThat(
            count(
                "SELECT count(*) FROM app_user WHERE id = ? AND account_status = 'ACTIVE'", userId))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM member_emergency_suspension WHERE user_id = ?", userId))
        .isEqualTo(2);
    assertThat(
            count(
                "SELECT count(*) FROM notification WHERE recipient_user_id = ? AND"
                    + " notification_type = 'SECURITY_ALERT'",
                userId))
        .isEqualTo(2);
    assertThat(auditCount("MEMBER_EMERGENCY_SUSPENDED")).isEqualTo(2);

    mockMvc
        .perform(
            get("/api/ops/members/" + userId)
                .cookie(sessionOf(operator("지원", OperatorRole.SUPPORT))))
        .andExpect(jsonPath("$.sessions", hasSize(0)))
        .andExpect(jsonPath("$.securityEvents", hasSize(2)))
        .andExpect(jsonPath("$.securityEvents[0].eventType").value("EMERGENCY_SUSPENDED"))
        .andExpect(jsonPath("$.securityEvents[0].scope").value("ALL_SESSIONS"))
        .andExpect(jsonPath("$.securityEvents[0].actorOperatorName").value("김보안"));
    assertError(
        post(
            "/api/ops/members/999999/emergency-suspend",
            "{\"reason\":\"사유\",\"scope\":\"ALL_SESSIONS\"," + review + "}",
            adminSession),
        ErrorCode.RESOURCE_NOT_FOUND);
  }
}
