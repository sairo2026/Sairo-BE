package com.sairo.be.domain.auth.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@IntegrationTest
class EmailLoginIntegrationTest extends AuthIntegrationTestBase {

  private static final String EMAIL = "agent@example.com";

  private String loginBody(String email, String password) {
    return "{\"email\":" + quote(email) + ",\"password\":" + quote(password) + "}";
  }

  @Test
  void 로그인에_성공하면_세션을_발급하고_접근_상태_판정을_돌려준다() throws Exception {
    saveEmailUser(EMAIL, "010-1234-5678");

    MvcResult result =
        mockMvc
            .perform(jsonPost("/api/auth/login", loginBody(EMAIL, PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessCondition").value("PRE_VERIFICATION"))
            .andExpect(jsonPath("$.officeId").doesNotExist())
            .andReturn();

    Cookie session = AuthFlowSupport.sessionCookie(result);
    assertThat(session).isNotNull();
    mockMvc
        .perform(get("/api/auth/context").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessCondition").value("PRE_VERIFICATION"));
  }

  @Test
  void 이메일은_대소문자와_앞뒤_공백을_무시한다() throws Exception {
    saveEmailUser(EMAIL, null);

    mockMvc
        .perform(jsonPost("/api/auth/login", loginBody("  Agent@Example.com ", PASSWORD)))
        .andExpect(status().isOk());
  }

  @Test
  void 로그인하면_로그인_이력에_방식_IP_환경을_남긴다() throws Exception {
    saveEmailUser(EMAIL, null);

    mockMvc.perform(
        jsonPost("/api/auth/login", loginBody(EMAIL, PASSWORD))
            .header(
                "User-Agent",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"));

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT auth_method, host(ip_address) AS ip, user_agent FROM login_history");
    assertThat(row.get("auth_method")).isEqualTo("EMAIL");
    assertThat(row.get("ip")).isEqualTo("127.0.0.1");
    assertThat((String) row.get("user_agent")).contains("Chrome");
  }

  @Test
  void 미가입_이메일과_비밀번호_오류는_같은_401이다() throws Exception {
    saveEmailUser(EMAIL, null);

    MvcResult unknown =
        mockMvc
            .perform(jsonPost("/api/auth/login", loginBody("none@example.com", PASSWORD)))
            .andReturn();
    MvcResult wrong =
        mockMvc
            .perform(jsonPost("/api/auth/login", loginBody(EMAIL, "wrong-password")))
            .andReturn();

    assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
    assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
    assertThat(AuthFlowSupport.json(unknown).get("code").asString())
        .isEqualTo("INVALID_CREDENTIALS");
    assertThat(AuthFlowSupport.json(wrong).get("code").asString()).isEqualTo("INVALID_CREDENTIALS");
    assertThat(AuthFlowSupport.json(unknown).get("message").asString())
        .isEqualTo(AuthFlowSupport.json(wrong).get("message").asString());
  }

  @Test
  void 탈퇴한_계정은_로그인할_수_없다() throws Exception {
    saveEmailUser(EMAIL, null);
    jdbcTemplate.update("UPDATE app_user SET account_status = 'WITHDRAWN', withdrawn_at = now()");

    assertError(
        mockMvc.perform(jsonPost("/api/auth/login", loginBody(EMAIL, PASSWORD))),
        ErrorCode.INVALID_CREDENTIALS);
  }

  @Test
  void 카카오_전용_계정은_이메일_로그인할_수_없다() throws Exception {
    appUserRepository.saveAndFlush(com.sairo.be.support.fixture.AppUserBuilder.anAppUser().build());

    assertError(
        mockMvc.perform(jsonPost("/api/auth/login", loginBody(EMAIL, PASSWORD))),
        ErrorCode.INVALID_CREDENTIALS);
  }

  @Test
  void 로그인_비밀번호에는_8자에서_12자_제한을_적용하지_않는다() throws Exception {
    saveEmailUser(EMAIL, null);

    assertError(
        mockMvc.perform(jsonPost("/api/auth/login", loginBody(EMAIL, "a"))),
        ErrorCode.INVALID_CREDENTIALS);
    assertError(
        mockMvc.perform(jsonPost("/api/auth/login", loginBody(EMAIL, "x".repeat(200)))),
        ErrorCode.INVALID_CREDENTIALS);
  }

  @Test
  void 연속_5회_실패하면_5번째까지는_401이고_이후에는_정답이어도_429다() throws Exception {
    saveEmailUser(EMAIL, null);

    for (int i = 0; i < 5; i++) {
      assertError(
          mockMvc.perform(jsonPost("/api/auth/login", loginBody(EMAIL, "wrong-password"))),
          ErrorCode.INVALID_CREDENTIALS);
    }

    assertError(
            mockMvc.perform(jsonPost("/api/auth/login", loginBody(EMAIL, PASSWORD))),
            ErrorCode.LOGIN_LOCKED)
        .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT failed_login_count, failed_login_locked_until FROM app_user");
    assertThat(row.get("failed_login_count")).isEqualTo(5);
    assertThat(row.get("failed_login_locked_until")).isNotNull();
  }

  @Test
  void 잠금은_IP와_계정_조합_기준이라_다른_계정은_영향받지_않는다() throws Exception {
    saveEmailUser(EMAIL, null);
    saveEmailUser("other@example.com", null);
    for (int i = 0; i < 5; i++) {
      mockMvc.perform(jsonPost("/api/auth/login", loginBody(EMAIL, "wrong-password")));
    }

    mockMvc
        .perform(jsonPost("/api/auth/login", loginBody("other@example.com", PASSWORD)))
        .andExpect(status().isOk());
  }

  @Test
  void 미가입_이메일도_같은_방식으로_잠가_계정_존재를_드러내지_않는다() throws Exception {
    for (int i = 0; i < 5; i++) {
      assertError(
          mockMvc.perform(jsonPost("/api/auth/login", loginBody("none@example.com", "x"))),
          ErrorCode.INVALID_CREDENTIALS);
    }

    assertError(
        mockMvc.perform(jsonPost("/api/auth/login", loginBody("none@example.com", "x"))),
        ErrorCode.LOGIN_LOCKED);
  }

  @Test
  void 로그인에_성공하면_실패_횟수를_초기화한다() throws Exception {
    saveEmailUser(EMAIL, null);
    for (int i = 0; i < 4; i++) {
      mockMvc.perform(jsonPost("/api/auth/login", loginBody(EMAIL, "wrong-password")));
    }
    mockMvc
        .perform(jsonPost("/api/auth/login", loginBody(EMAIL, PASSWORD)))
        .andExpect(status().isOk());

    for (int i = 0; i < 4; i++) {
      assertError(
          mockMvc.perform(jsonPost("/api/auth/login", loginBody(EMAIL, "wrong-password"))),
          ErrorCode.INVALID_CREDENTIALS);
    }
    Integer count =
        jdbcTemplate.queryForObject("SELECT failed_login_count FROM app_user", Integer.class);
    assertThat(count).isEqualTo(4);
  }

  @Test
  void 로그인하면_세션_ID를_새로_발급하고_이전_세션은_폐기한다() throws Exception {
    saveEmailUser(EMAIL, null);
    MvcResult anonymous = mockMvc.perform(get("/api/auth/kakao/start")).andReturn();
    Cookie before = AuthFlowSupport.sessionCookie(anonymous);
    assertThat(before).isNotNull();

    MvcResult result =
        mockMvc
            .perform(jsonPost("/api/auth/login", loginBody(EMAIL, PASSWORD), before))
            .andExpect(status().isOk())
            .andReturn();

    Cookie after = AuthFlowSupport.sessionCookie(result);
    assertThat(after).isNotNull();
    assertThat(after.getValue()).isNotEqualTo(before.getValue());
    mockMvc.perform(get("/api/auth/context").cookie(before)).andExpect(status().isUnauthorized());
  }

  @Test
  void 세션_쿠키는_HttpOnly_Secure_SameSite_Lax_Host_접두사를_가진다() throws Exception {
    saveEmailUser(EMAIL, null);

    MvcResult result =
        mockMvc.perform(jsonPost("/api/auth/login", loginBody(EMAIL, PASSWORD))).andReturn();

    List<String> headers = result.getResponse().getHeaders("Set-Cookie");
    String sessionHeader =
        headers.stream()
            .filter(h -> h.startsWith("__Host-sairo_session="))
            .findFirst()
            .orElseThrow();
    assertThat(sessionHeader)
        .contains("HttpOnly")
        .contains("Secure")
        .contains("SameSite=Lax")
        .contains("Path=/")
        .doesNotContain("Domain=");
  }

  @Test
  void 로그인_요청은_CSRF_토큰이_없으면_403이다() throws Exception {
    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(EMAIL, PASSWORD)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void 필수_입력이_비면_INVALID_REQUEST다() throws Exception {
    mockMvc
        .perform(jsonPost("/api/auth/login", "{\"email\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void 로그아웃은_현재_세션만_종료하고_204다() throws Exception {
    saveEmailUser(EMAIL, null);
    Cookie first = login(EMAIL, PASSWORD);
    Cookie second = login(EMAIL, PASSWORD);

    mockMvc.perform(jsonPost("/api/auth/logout", "{}", first)).andExpect(status().isNoContent());

    mockMvc.perform(get("/api/auth/context").cookie(first)).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/auth/context").cookie(second)).andExpect(status().isOk());
  }

  @Test
  void 로그인_없이_로그아웃하면_401이다() throws Exception {
    assertError(mockMvc.perform(jsonPost("/api/auth/logout", "{}")), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 로그인_IP당_분당_60회를_넘으면_429다() throws Exception {
    for (int i = 0; i < 60; i++) {
      mockMvc.perform(jsonPost("/api/auth/login", loginBody("u" + i + "@example.com", "x")));
    }

    assertError(
        mockMvc.perform(jsonPost("/api/auth/login", loginBody("last@example.com", "x"))),
        ErrorCode.RATE_LIMITED);
  }
}
