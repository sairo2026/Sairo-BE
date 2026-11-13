package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.ApproverAccountTestBase;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class ApproverPortalPrivacyIntakeIntegrationTest extends ApproverAccountTestBase {

  private static final String URL = PORTAL + "/privacy-intakes";

  private static String body(long approvalId, String extra) {
    return "{\"approvalId\":"
        + approvalId
        + ",\"requestType\":\"ACCESS\",\"requesterRole\":\"SELF\",\"contactChannel\":\"EMAIL\","
        + "\"contact\":\"hong@example.com\",\"requestNote\":\"입력한 조건을 확인하고 싶습니다\","
        + "\"acknowledgedNoticeVersion\":\""
        + NOTICE_VERSION
        + "\""
        + extra
        + "}";
  }

  private ResultActions send(String body, Cookie cookie) throws Exception {
    return mockMvc.perform(jsonPostAs(URL, body, cookie));
  }

  @Test
  void 승인자_세션으로_연결된_출입승인의_권리_요청을_접수하고_프로필로_안내_확인과_접속_기록을_남긴다() throws Exception {
    RequestFixture f = requestFixture();
    Cookie cookie = claim(issueApproverToken(f.cookie(), f.propertyId(), f.approvalId()));
    long profile = profileId();

    var result =
        send(body(f.approvalId(), ""), cookie)
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.contactChannel").value("EMAIL"))
            .andExpect(jsonPath("$.maskedContact").value("ho***@example.com"))
            .andReturn();
    assertThat(result.getResponse().getContentAsString()).doesNotContain("hong@example.com");

    Map<String, Object> intake =
        jdbcTemplate.queryForMap("SELECT * FROM legal_guest_privacy_intake");
    assertThat(intake.get("office_id")).isEqualTo(f.owner().office().getId());
    assertThat(intake.get("link_kind")).isEqualTo("APPROVER_PORTAL");
    assertThat(intake.get("link_id")).isEqualTo(profile);
    assertThat((String) intake.get("contact_encrypted")).doesNotContain("hong@example.com");
    assertThat(
            count(
                "SELECT count(*) FROM consent_guest_notice_ack WHERE link_kind = 'APPROVER_PORTAL'"
                    + " AND action_type = 'PRIVACY_INTAKE' AND approver_profile_id = ?",
                profile))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM legal_public_link_access_log WHERE link_kind ="
                    + " 'APPROVER_PORTAL' AND link_id = ? AND action_type = 'PRIVACY_INTAKE'",
                profile))
        .isEqualTo(1);
  }

  @Test
  void 세션이_없으면_401이고_연결되지_않은_출입승인은_404이며_안내_버전이_다르면_409이고_형식_오류는_400이다() throws Exception {
    RequestFixture f = requestFixture();
    Cookie cookie = claim(issueApproverToken(f.cookie(), f.propertyId(), f.approvalId()));
    long unlinked = otherPropertyApproval(f, null);

    assertError(send(body(f.approvalId(), ""), null), ErrorCode.UNAUTHENTICATED);
    assertError(send(body(unlinked, ""), cookie), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        send(body(f.approvalId(), "").replace(NOTICE_VERSION + "\"", "0.9\""), cookie),
        ErrorCode.NOTICE_VERSION_CHANGED);
    assertError(send(body(f.approvalId(), ",\"token\":\"x\""), cookie), ErrorCode.INVALID_REQUEST);
    assertError(
        send(body(f.approvalId(), "").replace("hong@example.com", "not-an-email"), cookie),
        ErrorCode.GUEST_CONTACT_INVALID);
    assertThat(count("SELECT count(*) FROM legal_guest_privacy_intake")).isZero();
  }

  @Test
  void 요청_내용에_주민등록번호_형식이_있으면_400이고_정상_접수의_내부_기한은_접수_3일_뒤다() throws Exception {
    RequestFixture f = requestFixture();
    Cookie cookie = claim(issueApproverToken(f.cookie(), f.propertyId(), f.approvalId()));

    assertError(
        send(
            body(f.approvalId(), "").replace("입력한 조건을 확인하고 싶습니다", "주민번호 900101-1234567 입니다"),
            cookie),
        ErrorCode.SENSITIVE_DATA_DETECTED);
    assertThat(count("SELECT count(*) FROM legal_guest_privacy_intake")).isZero();

    send(body(f.approvalId(), ""), cookie).andExpect(status().isCreated());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT internal_due_at - received_at = interval '3 days'"
                    + " FROM legal_guest_privacy_intake",
                Boolean.class))
        .isTrue();
  }
}
