package com.sairo.be.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

public abstract class ApproverAccountTestBase extends VisitReviewTestBase {

  protected static final String APPROVER_COOKIE = "APPROVER_SESSION";
  protected static final String PHONE = "010-1111-2222";
  protected static final String PORTAL = "/api/approver-portal";

  protected static String portalApproval(long approvalId) {
    return PORTAL + "/approvals/" + approvalId;
  }

  protected void resetRateLimits() {
    jdbcTemplate.update("DELETE FROM rate_limit_counter");
  }

  protected ResultActions sendOtp(String purpose, String phone, String token, Cookie cookie)
      throws Exception {
    resetRateLimits();
    String body =
        "{\"purpose\":\""
            + purpose
            + "\",\"phone\":\""
            + phone
            + "\""
            + (token == null ? "" : ",\"linkToken\":\"" + token + "\"")
            + "}";
    MockHttpServletRequestBuilder builder =
        post("/api/public/approver-auth/otp").contentType(MediaType.APPLICATION_JSON).content(body);
    if (cookie != null) {
      builder.cookie(cookie);
    }
    return mockMvc.perform(AuthFlowSupport.withCsrf(builder));
  }

  protected ResultActions verifyOtp(
      String purpose, String phone, String code, String token, String notice, Cookie cookie)
      throws Exception {
    String body =
        "{\"purpose\":\""
            + purpose
            + "\",\"phone\":\""
            + phone
            + "\",\"code\":\""
            + code
            + "\""
            + (token == null ? "" : ",\"linkToken\":\"" + token + "\"")
            + (notice == null ? "" : ",\"acknowledgedNoticeVersion\":\"" + notice + "\"")
            + "}";
    MockHttpServletRequestBuilder builder =
        post("/api/public/approver-auth/verify")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    if (cookie != null) {
      builder.cookie(cookie);
    }
    return mockMvc.perform(AuthFlowSupport.withCsrf(builder));
  }

  /** 링크 화면에서 계정을 만들고 승인자 세션 쿠키를 돌려준다. */
  protected Cookie claim(String token) throws Exception {
    sendOtp("CLAIM", PHONE, token, null).andExpect(status().isOk());
    MvcResult result =
        verifyOtp("CLAIM", PHONE, flow.lastSmsCode(PHONE), token, NOTICE_VERSION, null)
            .andExpect(status().isOk())
            .andReturn();
    return approverCookie(result);
  }

  protected Cookie login() throws Exception {
    sendOtp("LOGIN", PHONE, null, null).andExpect(status().isOk());
    MvcResult result =
        verifyOtp("LOGIN", PHONE, flow.lastSmsCode(PHONE), null, null, null)
            .andExpect(status().isOk())
            .andReturn();
    return approverCookie(result);
  }

  protected static Cookie approverCookie(MvcResult result) {
    Cookie cookie = result.getResponse().getCookie(APPROVER_COOKIE);
    if (cookie == null) {
      throw new IllegalStateException("승인자 세션 쿠키가 없습니다.");
    }
    return cookie;
  }

  protected ResultActions portalGet(String url, Cookie cookie) throws Exception {
    MockHttpServletRequestBuilder builder = get(url);
    if (cookie != null) {
      builder.cookie(cookie);
    }
    return mockMvc.perform(builder);
  }

  protected long profileId() {
    return count("SELECT id FROM approver_profile ORDER BY id DESC LIMIT 1");
  }

  protected long otherPropertyApproval(RequestFixture f, String role) throws Exception {
    long property = newProperty(f.cookie());
    return registerApproval(f.cookie(), property, role, -1, 30);
  }
}
