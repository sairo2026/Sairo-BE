package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.ApproverAccountTestBase;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

@IntegrationTest
class ApproverAccountIntegrationTest extends ApproverAccountTestBase {

  @Test
  void 링크_화면에서_계정을_만들면_링크_출입승인을_연결하고_같은_번호의_다른_출입승인을_제안한다() throws Exception {
    RequestFixture f = requestFixture();
    long other = otherPropertyApproval(f, null);
    String token = issueApproverToken(f.cookie(), f.propertyId(), f.approvalId());

    sendOtp("CLAIM", PHONE, token, null)
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.accepted").value(true))
        .andExpect(jsonPath("$.expiresInSeconds").value(300))
        .andExpect(jsonPath("$.resendAfterSeconds").value(60));
    Cookie cookie =
        approverCookie(
            verifyOtp("CLAIM", PHONE, flow.lastSmsCode(PHONE), token, NOTICE_VERSION, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileCreated").value(true))
                .andExpect(jsonPath("$.claimedApprovalId").value(f.approvalId()))
                .andExpect(jsonPath("$.linkedApprovalCount").value(1))
                .andExpect(jsonPath("$.pendingConfirmCount").value(1))
                .andExpect(jsonPath("$.maskedPhone").value("***-****-2222"))
                .andExpect(jsonPath("$.sessionIdleTimeoutMinutes").value(60))
                .andExpect(jsonPath("$.privacyNotice.noticeVersion").value(NOTICE_VERSION))
                .andReturn());

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT phone_last4 FROM approver_profile WHERE id = ?", String.class, profileId()))
        .isEqualTo("2222");
    assertThat(
            count("SELECT count(*) FROM approver_auth_event WHERE event_type = 'CLAIM_COMPLETED'"))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM consent_guest_notice_ack WHERE link_kind = 'APPROVER_PORTAL'"
                    + " AND action_type = 'PORTAL_CLAIM'"))
        .isEqualTo(1);

    portalGet(PORTAL + "/profile", cookie)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.linkedApprovalCount").value(1))
        .andExpect(jsonPath("$.pendingConfirmCount").value(1));
    portalGet(PORTAL + "/approvals?linkState=PENDING_CONFIRM", cookie)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].approvalId").value(other))
        .andExpect(jsonPath("$.items[0].linkState").value("PENDING_CONFIRM"))
        .andExpect(jsonPath("$.items[0].propertyDisplayName").doesNotExist())
        .andExpect(jsonPath("$.items[0].officeContactPhone").doesNotExist())
        .andExpect(jsonPath("$.items[0].pendingRequestCount").value(0));
    portalGet(PORTAL + "/approvals?linkState=LINKED", cookie)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].approvalId").value(f.approvalId()))
        .andExpect(jsonPath("$.items[0].approvalRole").value("PRIMARY"))
        .andExpect(jsonPath("$.items[0].approvalActive").value(true))
        .andExpect(jsonPath("$.items[0].activeConditionVersionNo").value(1))
        .andExpect(jsonPath("$.items[0].propertyDisplayName").isNotEmpty());

    mockMvc
        .perform(
            jsonPostAs(
                portalApproval(other) + "/link-decision", "{\"decision\":\"ACCEPT\"}", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.linkState").value("LINKED"));
    assertError(
        mockMvc.perform(
            jsonPostAs(
                portalApproval(other) + "/link-decision", "{\"decision\":\"REJECT\"}", cookie)),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        mockMvc.perform(
            jsonPostAs(
                portalApproval(other + 1000) + "/link-decision",
                "{\"decision\":\"ACCEPT\"}",
                cookie)),
        ErrorCode.RESOURCE_NOT_FOUND);

    mockMvc
        .perform(deleteAs(portalApproval(other) + "/link", cookie))
        .andExpect(status().isNoContent());
    assertError(
        mockMvc.perform(deleteAs(portalApproval(other) + "/link", cookie)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertThat(
            count(
                "SELECT count(*) FROM approver_auth_event WHERE event_type IN"
                    + " ('APPROVAL_LINKED','APPROVAL_UNLINKED')"))
        .isEqualTo(2);
  }

  @Test
  void 계정_만들기는_번호가_다르거나_안내_버전이_다르거나_인증번호가_틀리면_거절한다() throws Exception {
    RequestFixture f = requestFixture();
    String token = issueApproverToken(f.cookie(), f.propertyId(), f.approvalId());

    assertError(
        sendOtp("CLAIM", "010-9999-8888", token, null), ErrorCode.APPROVER_CLAIM_NOT_ALLOWED);
    assertThat(smsSender.sent()).isEmpty();
    assertError(sendOtp("CLAIM", PHONE, "invalid-token", null), ErrorCode.PUBLIC_LINK_NOT_FOUND);

    sendOtp("CLAIM", PHONE, token, null).andExpect(status().isOk());
    String code = flow.lastSmsCode(PHONE);
    String wrong = code.equals("000000") ? "111111" : "000000";
    assertError(
        verifyOtp("CLAIM", PHONE, code, token, "0.9", null), ErrorCode.NOTICE_VERSION_CHANGED);
    assertError(
            verifyOtp("CLAIM", PHONE, wrong, token, NOTICE_VERSION, null),
            ErrorCode.VERIFICATION_CODE_INVALID)
        .andExpect(jsonPath("$.remainingAttempts").value(4));
    assertThat(count("SELECT count(*) FROM approver_profile")).isZero();

    verifyOtp("CLAIM", PHONE, code, token, NOTICE_VERSION, null).andExpect(status().isOk());
    assertError(
        verifyOtp("CLAIM", PHONE, code, token, NOTICE_VERSION, null), ErrorCode.TOKEN_INVALID);
  }

  @Test
  void 계정이_없는_번호의_로그인은_같은_응답을_주고_문자를_보내지_않는다() throws Exception {
    sendOtp("LOGIN", "010-5555-6666", null, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accepted").value(true));
    assertThat(smsSender.sent()).isEmpty();
    assertError(
        verifyOtp("LOGIN", "010-5555-6666", "123456", null, null, null), ErrorCode.TOKEN_INVALID);
    assertError(sendOtp("REAUTH", PHONE, null, null), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 로그아웃하면_세션이_끝나고_번호_인증으로_다시_로그인한다() throws Exception {
    RequestFixture f = requestFixture();
    Cookie cookie = claim(issueApproverToken(f.cookie(), f.propertyId(), f.approvalId()));

    mockMvc.perform(deleteAs(PORTAL + "/session", cookie)).andExpect(status().isNoContent());
    assertError(portalGet(PORTAL + "/profile", cookie), ErrorCode.UNAUTHENTICATED);

    Cookie again = login();
    portalGet(PORTAL + "/profile", again)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.linkedApprovalCount").value(1));
    assertThat(
            count("SELECT count(*) FROM approver_auth_event WHERE event_type = 'LOGIN_SUCCEEDED'"))
        .isEqualTo(1);
  }

  @Test
  void 승인자_세션_경로는_승인자_쿠키로만_접근한다() throws Exception {
    RequestFixture f = requestFixture();
    Cookie cookie = claim(issueApproverToken(f.cookie(), f.propertyId(), f.approvalId()));
    Cookie memberAsApprover = new Cookie(APPROVER_COOKIE, f.cookie().getValue());

    assertError(mockMvc.perform(get(PORTAL + "/profile")), ErrorCode.UNAUTHENTICATED);
    assertError(portalGet(PORTAL + "/approvals", f.cookie()), ErrorCode.UNAUTHENTICATED);
    assertError(portalGet(PORTAL + "/approvals", memberAsApprover), ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(authedGet("/api/visit-trust/me", cookie)), ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(jsonPostAs(PORTAL + "/condition-bulk/preview", "{}", null)),
        ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(deleteAs(portalApproval(f.approvalId()) + "/link", null)),
        ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 계정_해지는_재인증이_필요하고_번호_정보와_연결을_지우며_세션을_끝낸다() throws Exception {
    RequestFixture f = requestFixture();
    Cookie cookie = claim(issueApproverToken(f.cookie(), f.propertyId(), f.approvalId()));

    assertError(
        mockMvc.perform(jsonPostAs(PORTAL + "/profile/close", "{\"confirm\":true}", cookie)),
        ErrorCode.STEP_UP_REQUIRED);

    sendOtp("REAUTH", "010-9999-8888", null, cookie);
    assertError(
        sendOtp("REAUTH", "010-9999-8888", null, cookie), ErrorCode.APPROVER_CLAIM_NOT_ALLOWED);
    sendOtp("REAUTH", PHONE, null, cookie).andExpect(status().isOk());
    verifyOtp("REAUTH", PHONE, flow.lastSmsCode(PHONE), null, null, cookie)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reauthValidUntil").isNotEmpty());

    mockMvc
        .perform(jsonPostAs(PORTAL + "/profile/close", "{\"confirm\":true}", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.unlinkedApprovalCount").value(1))
        .andExpect(jsonPath("$.endedSessionCount").value(1));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM approver_profile WHERE id = ?", String.class, profileId()))
        .isEqualTo("CLOSED");
    assertThat(
            count(
                "SELECT count(*) FROM approver_profile WHERE phone_hash IS NULL AND phone_last4 IS"
                    + " NULL"))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM approver_profile_approval WHERE status = 'UNLINKED'"))
        .isEqualTo(1);
    assertError(portalGet(PORTAL + "/profile", cookie), ErrorCode.UNAUTHENTICATED);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT approver_phone FROM property_access_approval WHERE id = ?",
                String.class,
                f.approvalId()))
        .isEqualTo(PHONE);
  }

  @Test
  void 조건_일괄_적용은_출입승인마다_결과를_알리고_영향_확인이_없으면_적용하지_않는다() throws Exception {
    RequestFixture f = requestFixture();
    long second = otherPropertyApproval(f, null);
    long coApproval = registerApproval(f.cookie(), f.propertyId(), "CO_APPROVER", -1, 30);
    Cookie cookie = claim(issueApproverToken(f.cookie(), f.propertyId(), f.approvalId()));
    for (long approval : new long[] {second, coApproval}) {
      mockMvc
          .perform(
              jsonPostAs(
                  portalApproval(approval) + "/link-decision", "{\"decision\":\"ACCEPT\"}", cookie))
          .andExpect(status().isOk());
    }
    long notLinked = otherPropertyApproval(f, null) + 500;
    String ids = "[" + f.approvalId() + "," + second + "," + coApproval + "," + notLinked + "]";

    mockMvc
        .perform(
            jsonPostAs(
                PORTAL + "/condition-bulk/preview",
                "{\"approvalIds\":" + ids + ",\"condition\":" + bulkCondition() + "}",
                cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].eligible").value(true))
        .andExpect(jsonPath("$.items[1].eligible").value(true))
        .andExpect(jsonPath("$.items[2].ineligibleReason").value("NOT_PRIMARY"))
        .andExpect(jsonPath("$.items[3].ineligibleReason").value("NOT_LINKED"))
        .andExpect(jsonPath("$.items[3].propertyDisplayName").doesNotExist());

    mockMvc
        .perform(
            jsonPostAs(
                PORTAL + "/condition-bulk",
                "{\"approvalIds\":"
                    + ids
                    + ",\"condition\":"
                    + bulkCondition()
                    + ",\"acknowledgedNoticeVersion\":\""
                    + NOTICE_VERSION
                    + "\"}",
                cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestedCount").value(4))
        .andExpect(jsonPath("$.appliedCount").value(2))
        .andExpect(jsonPath("$.failedCount").value(2))
        .andExpect(jsonPath("$.items[0].result").value("APPLIED"))
        .andExpect(jsonPath("$.items[0].versionNo").value(2))
        .andExpect(jsonPath("$.items[2].failureCode").value("NOT_PRIMARY"))
        .andExpect(jsonPath("$.items[3].failureCode").value("NOT_LINKED"));
    assertThat(
            count(
                "SELECT count(*) FROM visit_approver_condition WHERE status = 'ACTIVE' AND"
                    + " entered_by_profile_id = ? AND source = 'DIRECT'",
                profileId()))
        .isEqualTo(2);
    assertThat(
            count(
                "SELECT count(*) FROM approver_condition_bulk_batch WHERE applied_count = 2 AND"
                    + " failed_count = 2 AND completed_at IS NOT NULL"))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM approver_condition_bulk_item")).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM approver_auth_event WHERE event_type = 'BULK_APPLIED'"))
        .isEqualTo(1);

    String tooMany =
        LongStream.rangeClosed(1, 21).mapToObj(String::valueOf).collect(Collectors.joining(","));
    assertError(
        mockMvc.perform(
            jsonPostAs(
                PORTAL + "/condition-bulk/preview",
                "{\"approvalIds\":[" + tooMany + "],\"condition\":" + bulkCondition() + "}",
                cookie)),
        ErrorCode.BULK_LIMIT_EXCEEDED);
    assertError(
        mockMvc.perform(
            jsonPostAs(
                PORTAL + "/condition-bulk",
                "{\"approvalIds\":"
                    + ids
                    + ",\"condition\":"
                    + bulkCondition()
                    + ",\"acknowledgedNoticeVersion\":\"0.9\"}",
                cookie)),
        ErrorCode.NOTICE_VERSION_CHANGED);
  }

  @Test
  void 영향_방문이_있는_출입승인은_확인_목록에_없으면_영향_확인_필요로_남긴다() throws Exception {
    SelectionFixture s = confirmed();
    RequestFixture f = s.base();
    Cookie cookie = claim(s.token());
    String strict = bulkCondition(earlyWindows());

    mockMvc
        .perform(
            jsonPostAs(
                PORTAL + "/condition-bulk",
                "{\"approvalIds\":["
                    + f.approvalId()
                    + "],\"condition\":"
                    + strict
                    + ",\"acknowledgedNoticeVersion\":\""
                    + NOTICE_VERSION
                    + "\"}",
                cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.needsImpactAckCount").value(1))
        .andExpect(jsonPath("$.items[0].result").value("NEEDS_IMPACT_ACK"))
        .andExpect(jsonPath("$.items[0].affectedItemCount").value(1));
    mockMvc
        .perform(
            jsonPostAs(
                PORTAL + "/condition-bulk",
                "{\"approvalIds\":["
                    + f.approvalId()
                    + "],\"impactAcknowledgedApprovalIds\":["
                    + f.approvalId()
                    + "],\"condition\":"
                    + strict
                    + ",\"acknowledgedNoticeVersion\":\""
                    + NOTICE_VERSION
                    + "\"}",
                cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.appliedCount").value(1));
    assertThat(coordinationStatus(f.coordinationId())).isEqualTo("RESCHEDULE_REQUIRED");
  }

  private String bulkCondition() {
    return bulkCondition(null);
  }

  private String bulkCondition(String windows) {
    java.util.Map<String, String> overrides = new java.util.HashMap<>();
    overrides.put("impactAcknowledged", null);
    overrides.put("acknowledgedNoticeVersion", null);
    if (windows != null) {
      overrides.put("windows", windows);
    }
    return conditionJson(false, overrides);
  }

  private static String earlyWindows() {
    return java.util.stream.IntStream.rangeClosed(1, 7)
        .mapToObj(
            day ->
                "{\"windowType\":\"WEEKDAY\",\"weekday\":"
                    + day
                    + ",\"windowDate\":null,\"startsTime\":\"05:00\",\"endsTime\":\"06:00\","
                    + "\"isException\":false}")
        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
  }
}
