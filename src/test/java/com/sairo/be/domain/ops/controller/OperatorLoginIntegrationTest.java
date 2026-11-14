package com.sairo.be.domain.ops.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsIntegrationTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

@IntegrationTest
class OperatorLoginIntegrationTest extends OpsIntegrationTestBase {

  private static final String KAKAO_ID = "7001001";

  private static void assertInvalidRedirect(
      org.springframework.test.web.servlet.ResultActions result) throws Exception {
    result
        .andExpect(status().isFound())
        .andExpect(
            header()
                .string(
                    "Location",
                    org.hamcrest.Matchers.endsWith("/ops/login?error=INVALID_REQUEST")));
  }

  private Cookie pendingCookie(String kakaoId) throws Exception {
    OpsStart start = startOps("/api/ops/auth/kakao/start");
    assertThat(start.location()).endsWith("flow=OPERATOR");
    MvcResult result =
        mockMvc
            .perform(
                get("/api/ops/auth/kakao/callback")
                    .cookie(start.cookie())
                    .param("code", kakaoId)
                    .param("state", start.state()))
            .andExpect(status().isFound())
            .andReturn();
    return opsCookieOf(result);
  }

  private MvcResult verify(Cookie cookie, String code) throws Exception {
    return mockMvc
        .perform(jsonPost("/api/ops/auth/mfa/verify", "{\"otpCode\":\"" + code + "\"}", cookie))
        .andReturn();
  }

  @Test
  void 카카오_시작은_인가_페이지로_이동하고_운영자_쿠키를_발급한다() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/ops/auth/kakao/start"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("state=")))
            .andReturn();
    assertThat(opsCookieOf(result)).isNotNull();
    assertThat(result.getResponse().getCookie("__Host-sairo_session")).isNull();
  }

  @Test
  void 콜백은_화이트리스트_운영자에게_2차_인증_화면으로_이동시키고_대기_쿠키를_준다() throws Exception {
    createOperator(KAKAO_ID, "김운영", OperatorRole.OPS);
    OpsStart start = startOps("/api/ops/auth/kakao/start");
    MvcResult result =
        mockMvc
            .perform(
                get("/api/ops/auth/kakao/callback")
                    .cookie(start.cookie())
                    .param("code", KAKAO_ID)
                    .param("state", start.state()))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/ops/mfa")))
            .andReturn();
    Cookie pending = opsCookieOf(result);
    assertThat(pending).isNotNull();
    assertThat(pending.getValue()).isNotEqualTo(start.cookie().getValue());
    assertError(mockMvc.perform(get("/api/ops/me").cookie(pending)), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 콜백은_없거나_비활성인_운영자를_같은_코드로_돌려보내고_쿠키를_주지_않는다() throws Exception {
    var disabled = createOperator("7001002", "비활성", OperatorRole.OPS);
    jdbcTemplate.update(
        "UPDATE operator_account SET status = 'DISABLED' WHERE id = ?", disabled.id());
    for (String kakaoId : new String[] {"7001002", "7009999"}) {
      OpsStart start = startOps("/api/ops/auth/kakao/start");
      MvcResult result =
          mockMvc
              .perform(
                  get("/api/ops/auth/kakao/callback")
                      .cookie(start.cookie())
                      .param("code", kakaoId)
                      .param("state", start.state()))
              .andExpect(status().isFound())
              .andExpect(
                  header()
                      .string(
                          "Location",
                          org.hamcrest.Matchers.endsWith("/ops/login?error=OPERATOR_NOT_ALLOWED")))
              .andReturn();
      Cookie issued = opsCookieOf(result);
      assertThat(issued == null || issued.getMaxAge() == 0).isTrue();
    }
  }

  @Test
  void 콜백은_state나_코드가_틀리면_로그인_화면으로_302_이동한다() throws Exception {
    createOperator(KAKAO_ID, "김운영", OperatorRole.OPS);
    OpsStart start = startOps("/api/ops/auth/kakao/start");
    assertInvalidRedirect(
        mockMvc.perform(
            get("/api/ops/auth/kakao/callback")
                .cookie(start.cookie())
                .param("code", KAKAO_ID)
                .param("state", "wrong-state")));
    OpsStart second = startOps("/api/ops/auth/kakao/start");
    assertInvalidRedirect(
        mockMvc.perform(
            get("/api/ops/auth/kakao/callback")
                .cookie(second.cookie())
                .param("state", second.state())
                .param("error", "access_denied")));
    assertInvalidRedirect(
        mockMvc.perform(get("/api/ops/auth/kakao/callback").param("code", KAKAO_ID)));
  }

  @Test
  void 올바른_코드는_운영자_세션을_만들고_내_정보와_역할을_돌려준다() throws Exception {
    var operator = createOperator(KAKAO_ID, "김운영", OperatorRole.OPS, OperatorRole.REVIEW);
    Cookie pending = pendingCookie(KAKAO_ID);
    MvcResult result = verify(pending, otp(operator));
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    Cookie session = opsCookieOf(result);
    assertThat(session).isNotNull();
    assertThat(session.getValue()).isNotEqualTo(pending.getValue());
    mockMvc
        .perform(get("/api/ops/me").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.operatorId").value(operator.id()))
        .andExpect(jsonPath("$.name").value("김운영"))
        .andExpect(jsonPath("$.roles[0]").value("OPS"))
        .andExpect(jsonPath("$.roles[1]").value("REVIEW"));
    String body = result.getResponse().getContentAsString();
    assertThat(body).contains("idleExpiresAt").contains("absoluteExpiresAt");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT mfa_failed_count FROM operator_account WHERE id = ?",
                Integer.class,
                operator.id()))
        .isZero();
  }

  @Test
  void 틀린_코드는_401이고_5번_연속_틀리면_잠겨_429이며_잠금_중에는_맞아도_거절된다() throws Exception {
    var operator = createOperator(KAKAO_ID, "김운영", OperatorRole.OPS);
    Cookie pending = pendingCookie(KAKAO_ID);
    String wrong = wrongOtp(operator.secret());
    for (int attempt = 1; attempt <= 4; attempt++) {
      assertError(
          mockMvc.perform(
              jsonPost("/api/ops/auth/mfa/verify", "{\"otpCode\":\"" + wrong + "\"}", pending)),
          ErrorCode.MFA_CODE_INVALID);
    }
    MvcResult locked = verify(pending, wrong);
    assertThat(locked.getResponse().getStatus()).isEqualTo(429);
    assertThat(locked.getResponse().getContentAsString()).contains("MFA_LOCKED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT mfa_locked_until > now() FROM operator_account WHERE id = ?",
                Boolean.class,
                operator.id()))
        .isTrue();
    assertThat(verify(pending, otp(operator)).getResponse().getStatus()).isEqualTo(429);

    jdbcTemplate.update(
        "UPDATE operator_account SET mfa_locked_until = now() - interval '1 second' WHERE id = ?",
        operator.id());
    assertThat(verify(pending, otp(operator)).getResponse().getStatus()).isEqualTo(200);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT mfa_failed_count FROM operator_account WHERE id = ?",
                Integer.class,
                operator.id()))
        .isZero();
  }

  @Test
  void 코드_확인은_대기_쿠키가_없거나_형식이_틀리면_거절한다() throws Exception {
    var operator = createOperator(KAKAO_ID, "김운영", OperatorRole.OPS);
    assertError(
        mockMvc.perform(jsonPost("/api/ops/auth/mfa/verify", "{\"otpCode\":\"123456\"}")),
        ErrorCode.UNAUTHENTICATED);
    Cookie pending = pendingCookie(KAKAO_ID);
    assertError(
        mockMvc.perform(jsonPost("/api/ops/auth/mfa/verify", "{\"otpCode\":\"12\"}", pending)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(jsonPost("/api/ops/auth/mfa/verify", "{}", pending)),
        ErrorCode.INVALID_REQUEST);
    jdbcTemplate.update(
        "UPDATE operator_account SET status = 'DISABLED' WHERE id = ?", operator.id());
    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/ops/auth/mfa/verify", "{\"otpCode\":\"" + otp(operator) + "\"}", pending)),
        ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 운영자_경로는_운영자_세션만_받고_회원_세션은_403이다() throws Exception {
    AppUser member = saveEmailUser("member@example.com", "010-4040-5050");
    String memberSessionId =
        sessions.createMemberSession(
            new com.sairo.be.global.security.StaffPrincipal(
                member.getId(), null, null, member.getName(), null),
            java.time.Instant.now(),
            java.time.Instant.now());
    Cookie memberUnderOpsName = opsCookie(memberSessionId);
    assertError(
        mockMvc.perform(get("/api/ops/me").cookie(memberUnderOpsName)), ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(get("/api/ops/me")), ErrorCode.UNAUTHENTICATED);
    var operator = createOperator(KAKAO_ID, "김운영", OperatorRole.OPS);
    Cookie operatorUnderMemberName =
        sessions.cookie(
            sessions.createOperatorSession(
                operator.principal(), java.time.Instant.now(), java.time.Instant.now()));
    assertError(
        mockMvc.perform(get("/api/me").cookie(operatorUnderMemberName)), ErrorCode.FORBIDDEN);
  }

  @Test
  void 재인증은_5분_유효기간을_주고_틀린_코드는_401_잠금은_429다() throws Exception {
    var operator = createOperator(KAKAO_ID, "김운영", OperatorRole.OPS);
    Cookie session = operatorSession(operator);
    MvcResult ok =
        mockMvc
            .perform(
                jsonPost(
                    "/api/ops/auth/step-up", "{\"otpCode\":\"" + otp(operator) + "\"}", session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stepUpValidUntil").exists())
            .andReturn();
    assertThat(ok.getResponse().getContentAsString()).contains("stepUpValidUntil");
    assertThat(sessionAttribute(session, "OPS_STEP_UP_UNTIL")).isNotNull();

    String wrong = wrongOtp(operator.secret());
    for (int attempt = 1; attempt <= 4; attempt++) {
      assertError(
          mockMvc.perform(
              jsonPost("/api/ops/auth/step-up", "{\"otpCode\":\"" + wrong + "\"}", session)),
          ErrorCode.MFA_CODE_INVALID);
    }
    assertThat(
            mockMvc
                .perform(
                    jsonPost("/api/ops/auth/step-up", "{\"otpCode\":\"" + wrong + "\"}", session))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(429);
    assertError(
        mockMvc.perform(jsonPost("/api/ops/auth/step-up", "{\"otpCode\":\"123456\"}")),
        ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(jsonPost("/api/ops/auth/step-up", "{\"otpCode\":\"x\"}", session)),
        ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 로그아웃은_세션을_폐기하고_없으면_401이다() throws Exception {
    var operator = createOperator(KAKAO_ID, "김운영", OperatorRole.OPS);
    Cookie session = operatorSession(operator);
    mockMvc
        .perform(jsonPost("/api/ops/auth/logout", "", session))
        .andExpect(status().isNoContent());
    assertError(mockMvc.perform(get("/api/ops/me").cookie(session)), ErrorCode.UNAUTHENTICATED);
    assertError(mockMvc.perform(jsonPost("/api/ops/auth/logout", "")), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 운영자_세션은_30분_유휴와_8시간_절대_만료를_쓴다() throws Exception {
    var operator = createOperator(KAKAO_ID, "김운영", OperatorRole.OPS);
    String sessionId =
        sessions.createOperatorSession(
            operator.principal(),
            java.time.Instant.now().minusSeconds(8 * 3600 + 5),
            java.time.Instant.now());
    assertError(
        mockMvc.perform(get("/api/ops/me").cookie(opsCookie(sessionId))),
        ErrorCode.UNAUTHENTICATED);
    String fresh =
        sessions.createOperatorSession(
            operator.principal(), java.time.Instant.now(), java.time.Instant.now());
    mockMvc.perform(get("/api/ops/me").cookie(opsCookie(fresh))).andExpect(status().isOk());
  }
}
