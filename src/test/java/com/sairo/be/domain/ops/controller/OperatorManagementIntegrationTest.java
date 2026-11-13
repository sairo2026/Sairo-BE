package com.sairo.be.domain.ops.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.ops.service.OperatorInvitationAuthService;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.MailSender.MailMessage;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsConsoleTestBase;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class OperatorManagementIntegrationTest extends OpsConsoleTestBase {

  private Operator admin;
  private Cookie adminSession;

  @BeforeEach
  void setUp() {
    admin = operator("박보안", OperatorRole.SECURITY_ADMIN);
    adminSession = sessionOf(admin);
  }

  private ResultActions cancel(long invitationId, String ifMatch, Cookie cookie) throws Exception {
    var builder = jsonPost("/api/ops/operator-invitations/" + invitationId + "/cancel", "", cookie);
    if (ifMatch != null) {
      builder.header("If-Match", ifMatch);
    }
    return mockMvc.perform(builder);
  }

  @Test
  void 운영자_초대는_재인증_뒤_해시만_저장하고_메일로_링크를_보낸다() throws Exception {
    String body =
        "{\"inviteeName\":\"김운영\",\"inviteeEmail\":\"Ops.New@Example.com\","
            + "\"roles\":[\"SUPPORT\",\"REVIEW\",\"SUPPORT\"]}";
    assertError(
        post("/api/ops/operator-invitations", body, adminSession), ErrorCode.STEP_UP_REQUIRED);
    expireStepUp(adminSession);
    assertError(
        post("/api/ops/operator-invitations", body, adminSession), ErrorCode.STEP_UP_REQUIRED);
    stepUp(adminSession);
    assertError(
        post(
            "/api/ops/operator-invitations",
            "{\"inviteeName\":\"김운영\",\"inviteeEmail\":\"bad\",\"roles\":[\"SUPPORT\"]}",
            adminSession),
        ErrorCode.INVALID_REQUEST);
    assertError(
        post(
            "/api/ops/operator-invitations",
            "{\"inviteeName\":\"김운영\",\"inviteeEmail\":\"a@b.com\",\"roles\":[\"ROOT\"]}",
            adminSession),
        ErrorCode.INVALID_REQUEST);
    assertError(
        post(
            "/api/ops/operator-invitations",
            "{\"inviteeName\":\"김운영\",\"inviteeEmail\":\"a@b.com\",\"roles\":[]}",
            adminSession),
        ErrorCode.INVALID_REQUEST);

    String created =
        post("/api/ops/operator-invitations", body, adminSession)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.inviteeEmail").value("ops.new@example.com"))
            .andExpect(jsonPath("$.roles", hasSize(2)))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.token").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long id = Long.parseLong(created.replaceAll("(?s).*\"invitationId\":(\\d+).*", "$1"));
    assertThat(mailSender.sent()).hasSize(1);
    MailMessage mail = mailSender.sent().get(0);
    assertThat(mail.to()).isEqualTo("ops.new@example.com");
    Matcher matcher = Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(mail.body());
    assertThat(matcher.find()).isTrue();
    String storedHash =
        jdbcTemplate.queryForObject(
            "SELECT token_hash FROM operator_invitation WHERE id = ?", String.class, id);
    assertThat(storedHash).isEqualTo(OperatorInvitationAuthService.sha256(matcher.group(1)));
    assertThat(storedHash).isNotEqualTo(matcher.group(1));
    assertThat(
            count(
                "SELECT count(*) FROM operator_invitation WHERE id = ? AND expires_at = created_at"
                    + " + interval '72 hours'",
                id))
        .isEqualTo(1);
    assertThat(auditCount("OPERATOR_INVITATION_CREATED")).isEqualTo(1);

    assertError(
        post("/api/ops/operator-invitations", body, adminSession),
        ErrorCode.INVITATION_ALREADY_PENDING);
  }

  @Test
  void 만료된_대기_초대가_있으면_만료로_정리하고_새로_만든다() throws Exception {
    long old = insertInvitation("expired-token", "옛초대", "PENDING", -1, OperatorRole.OPS);
    stepUp(adminSession);
    post(
            "/api/ops/operator-invitations",
            "{\"inviteeName\":\"새초대\",\"inviteeEmail\":\"expired-token@example.com\","
                + "\"roles\":[\"OPS\"]}",
            adminSession)
        .andExpect(status().isCreated());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM operator_invitation WHERE id = ?", String.class, old))
        .isEqualTo("EXPIRED");
    assertThat(auditCount("OPERATOR_INVITATION_EXPIRED")).isEqualTo(1);
  }

  @Test
  void 초대_목록은_상태로_거르고_기한이_지난_대기_초대는_만료로_보인다() throws Exception {
    insertInvitation("tok-pending", "대기", "PENDING", 10, OperatorRole.SUPPORT);
    insertInvitation("tok-stale", "지난대기", "PENDING", -1, OperatorRole.OPS);
    insertInvitation("tok-cancel", "취소", "CANCELLED", 10, OperatorRole.OPS);
    mockMvc
        .perform(get("/api/ops/operator-invitations").cookie(adminSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(3));
    mockMvc
        .perform(get("/api/ops/operator-invitations?status=PENDING").cookie(adminSession))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].inviteeName").value("대기"))
        .andExpect(jsonPath("$.items[0].roles[0]").value("SUPPORT"))
        .andExpect(jsonPath("$.items[0].invitedByName").value("초대자"));
    mockMvc
        .perform(get("/api/ops/operator-invitations?status=EXPIRED").cookie(adminSession))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].inviteeName").value("지난대기"));
    assertError(
        mockMvc.perform(get("/api/ops/operator-invitations?status=X").cookie(adminSession)),
        ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 초대_취소는_재인증과_버전을_확인하고_대기_초대만_취소한다() throws Exception {
    long pending = insertInvitation("tok-a", "대기", "PENDING", 10, OperatorRole.SUPPORT);
    long stale = insertInvitation("tok-b", "지난대기", "PENDING", -1, OperatorRole.SUPPORT);
    assertError(cancel(pending, "1", adminSession), ErrorCode.STEP_UP_REQUIRED);
    stepUp(adminSession);
    assertError(cancel(pending, null, adminSession), ErrorCode.INVALID_REQUEST);
    assertError(cancel(999999, "1", adminSession), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(cancel(pending, "5", adminSession), ErrorCode.VERSION_CONFLICT)
        .andExpect(jsonPath("$.currentVersion").value(1));
    cancel(pending, "1", adminSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.cancelledAt").exists())
        .andExpect(jsonPath("$.version").value(2));
    assertError(cancel(pending, "2", adminSession), ErrorCode.INVALID_TRANSITION);
    assertError(cancel(stale, "1", adminSession), ErrorCode.INVALID_TRANSITION);
    assertThat(auditCount("OPERATOR_INVITATION_CANCELLED")).isEqualTo(1);
  }

  @Test
  void 역할_배정은_전체_목록으로_바꾸고_세션의_역할도_바꾸며_마지막_보안관리자는_지킨다() throws Exception {
    Operator target = operator("이지원", OperatorRole.SUPPORT);
    Cookie targetSession = sessionOf(target);
    String path = "/api/ops/operators/" + target.id() + "/roles";
    assertError(post(path, "{\"roles\":[\"OPS\"]}", adminSession), ErrorCode.STEP_UP_REQUIRED);
    stepUp(adminSession);
    assertError(post(path, "{\"roles\":[]}", adminSession), ErrorCode.INVALID_REQUEST);
    assertError(
        post("/api/ops/operators/999999/roles", "{\"roles\":[\"OPS\"]}", adminSession),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(get("/api/ops/offices/1").cookie(targetSession)),
        ErrorCode.RESOURCE_NOT_FOUND);

    post(path, "{\"roles\":[\"REVIEW\",\"PRIVACY\"]}", adminSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.operatorId").value(target.id()))
        .andExpect(jsonPath("$.roles", hasSize(2)))
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    assertThat(auditCount("OPERATOR_ROLE_GRANTED")).isEqualTo(2);
    assertThat(auditCount("OPERATOR_ROLE_REVOKED")).isEqualTo(1);
    assertError(
        mockMvc.perform(get("/api/ops/visit-review-disputes").cookie(targetSession)),
        ErrorCode.FORBIDDEN);
    mockMvc
        .perform(get("/api/ops/office-registrations").cookie(targetSession))
        .andExpect(status().isOk());

    assertError(
        post("/api/ops/operators/" + admin.id() + "/roles", "{\"roles\":[\"OPS\"]}", adminSession),
        ErrorCode.LAST_SECURITY_ADMIN);
    mockMvc
        .perform(get("/api/ops/operators").cookie(adminSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.items[0].operatorId").value(admin.id()))
        .andExpect(jsonPath("$.items[0].roles[0]").value("SECURITY_ADMIN"));
  }

  @Test
  void 비활성화는_세션을_끝내고_미실행_승인을_무효로_하며_재활성화할_수_있다() throws Exception {
    Operator target = operator("최개인", OperatorRole.PRIVACY);
    String targetSessionId =
        sessions.createOperatorSession(target.principal(), Instant.now(), Instant.now());
    long requestId =
        jdbcTemplate.queryForObject(
            "INSERT INTO ops_approval_request (request_type, requested_by_operator_id,"
                + " target_type, target_id, purpose, scope, basis_type, basis_note, status)"
                + " VALUES ('RAW_VIEW', ?, 'MEMBER', 1, '목적', '범위', 'LEGAL_BASIS', '근거',"
                + " 'PENDING') RETURNING id",
            Long.class,
            target.id());
    String disable = "/api/ops/operators/" + target.id() + "/disable";
    assertError(post(disable, "{\"reason\":\"퇴사\"}", adminSession), ErrorCode.STEP_UP_REQUIRED);
    stepUp(adminSession);
    assertError(post(disable, "{\"reason\":\"\"}", adminSession), ErrorCode.INVALID_REQUEST);
    assertError(
        post("/api/ops/operators/" + admin.id() + "/disable", "{\"reason\":\"퇴사\"}", adminSession),
        ErrorCode.FORBIDDEN);
    assertError(
        post("/api/ops/operators/999999/disable", "{\"reason\":\"퇴사\"}", adminSession),
        ErrorCode.RESOURCE_NOT_FOUND);

    post(disable, "{\"reason\":\"퇴사\"}", adminSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DISABLED"))
        .andExpect(jsonPath("$.terminatedSessionCount").value(1))
        .andExpect(jsonPath("$.invalidatedApprovalCount").value(1));
    assertThat(sessions.exists(targetSessionId)).isFalse();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM ops_approval_request WHERE id = ?", String.class, requestId))
        .isEqualTo("INVALIDATED");
    assertError(post(disable, "{\"reason\":\"퇴사\"}", adminSession), ErrorCode.INVALID_TRANSITION);

    String enable = "/api/ops/operators/" + target.id() + "/enable";
    post(enable, "", adminSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    assertError(post(enable, "", adminSession), ErrorCode.INVALID_TRANSITION);
    assertThat(auditCount("OPERATOR_DISABLED")).isEqualTo(1);
    assertThat(auditCount("OPERATOR_ENABLED")).isEqualTo(1);
  }

  @Test
  void 감사_이력은_조건으로_거르고_형식이_틀리면_400이다() throws Exception {
    Operator target = operator("이지원", OperatorRole.SUPPORT);
    stepUp(adminSession);
    post("/api/ops/operators/" + target.id() + "/roles", "{\"roles\":[\"OPS\"]}", adminSession)
        .andExpect(status().isOk());
    mockMvc
        .perform(
            get("/api/ops/audit-log?actionType=OPERATOR_ROLE_GRANTED&targetType=OPERATOR&targetId="
                    + target.id())
                .cookie(adminSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].operatorAccountId").value(admin.id()))
        .andExpect(jsonPath("$.items[0].operatorName").value("박보안"))
        .andExpect(jsonPath("$.items[0].detail").value("OPS"));
    mockMvc
        .perform(get("/api/ops/audit-log?operatorId=" + admin.id()).cookie(adminSession))
        .andExpect(jsonPath("$.totalElements").value(2));
    mockMvc
        .perform(get("/api/ops/audit-log?from=2099-01-01").cookie(adminSession))
        .andExpect(jsonPath("$.totalElements").value(0));
    assertError(
        mockMvc.perform(get("/api/ops/audit-log?actionType=bad-value").cookie(adminSession)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(get("/api/ops/audit-log?from=yesterday").cookie(adminSession)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(
            get("/api/ops/audit-log?from=2026-02-01&to=2026-01-01").cookie(adminSession)),
        ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 운영자_선택_목록은_모든_역할이_보고_활성_운영자의_이름과_역할만_이름순으로_준다() throws Exception {
    String path = "/api/ops/operators/options";
    Operator support = operator("가지원", OperatorRole.SUPPORT, OperatorRole.REVIEW);
    Operator disabled = operator("나비활성", OperatorRole.OPS);
    jdbcTemplate.update(
        "UPDATE operator_account SET status = 'DISABLED' WHERE id = ?", disabled.id());
    Operator noRole = operator("다무역할");

    assertError(mockMvc.perform(get(path)), ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(get(path).cookie(memberSession(staff(OfficeMembershipRole.OWNER)))),
        ErrorCode.UNAUTHENTICATED);
    String body =
        mockMvc
            .perform(get(path).cookie(sessionOf(support)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items", hasSize(3)))
            .andExpect(jsonPath("$.items[0].operatorId").value(support.id()))
            .andExpect(jsonPath("$.items[0].name").value("가지원"))
            .andExpect(jsonPath("$.items[0].roles[0]").value("SUPPORT"))
            .andExpect(jsonPath("$.items[0].roles[1]").value("REVIEW"))
            .andExpect(jsonPath("$.items[1].operatorId").value(noRole.id()))
            .andExpect(jsonPath("$.items[1].roles", hasSize(0)))
            .andExpect(jsonPath("$.items[2].operatorId").value(admin.id()))
            .andExpect(jsonPath("$.items[2].roles[0]").value("SECURITY_ADMIN"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body)
        .doesNotContain("나비활성")
        .doesNotContain("createdAt")
        .doesNotContain("status")
        .doesNotContain(support.kakaoKey());
    mockMvc
        .perform(get(path).cookie(sessionOf(noRole)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", hasSize(3)));
  }
}
