package com.sairo.be.domain.auth.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.auth.service.AuthRateLimits;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.support.RequestRateLimiter;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.AppUserBuilder;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class PasswordResetIntegrationTest extends AuthIntegrationTestBase {

  private static final String EMAIL = "agent@example.com";

  @Autowired private RequestRateLimiter rateLimiter;

  private String requestBody(String email) {
    return "{\"email\":\"" + email + "\"}";
  }

  private String resetBody(String token, String password) {
    return "{\"token\":" + quote(token) + ",\"newPassword\":" + quote(password) + "}";
  }

  private String requestToken() throws Exception {
    mockMvc
        .perform(jsonPost("/api/auth/password/reset-request", requestBody(EMAIL)))
        .andExpect(status().isOk());
    return flow.lastMailToken(EMAIL);
  }

  @Test
  void 가입된_이메일이면_링크를_보내고_토큰은_해시로만_저장한다() throws Exception {
    saveEmailUser(EMAIL, null);

    mockMvc
        .perform(jsonPost("/api/auth/password/reset-request", requestBody(EMAIL)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accepted").value(true));

    String token = flow.lastMailToken(EMAIL);
    String hash =
        jdbcTemplate.queryForObject("SELECT token_hash FROM password_reset_token", String.class);
    assertThat(hash).hasSize(64).isNotEqualTo(token);
  }

  @Test
  void 가입하지_않은_이메일도_같은_응답이고_메일을_보내지_않는다() throws Exception {
    var registered =
        mockMvc
            .perform(jsonPost("/api/auth/password/reset-request", requestBody("none@example.com")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted").value(true))
            .andReturn();

    assertThat(registered.getResponse().getContentAsString()).isEqualTo("{\"accepted\":true}");
    assertThat(mailSender.sent()).isEmpty();
  }

  @Test
  void 이메일_로그인이_없는_카카오_계정에는_링크를_보내지_않는다() throws Exception {
    appUserRepository.saveAndFlush(AppUserBuilder.anAppUser().build());

    mockMvc
        .perform(jsonPost("/api/auth/password/reset-request", requestBody(EMAIL)))
        .andExpect(status().isOk());

    assertThat(mailSender.sent()).isEmpty();
  }

  @Test
  void 이메일_형식이_아니면_400이다() throws Exception {
    assertFieldError(
        mockMvc.perform(jsonPost("/api/auth/password/reset-request", requestBody("not-mail"))),
        "email",
        "INVALID_FORMAT");
  }

  @Test
  void 재요청하면_이전_링크는_무효이고_사용자당_활성_링크는_하나다() throws Exception {
    saveEmailUser(EMAIL, null);
    String first = requestToken();
    rateLimiter.reset(AuthRateLimits.PASSWORD_RESET_INTERVAL, EMAIL);
    String second = requestToken();

    assertThat(second).isNotEqualTo(first);
    assertError(
        mockMvc.perform(jsonPost("/api/auth/password/reset", resetBody(first, "Newpass#12"))),
        ErrorCode.TOKEN_INVALID);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM password_reset_token"
                    + " WHERE consumed_at IS NULL AND superseded_at IS NULL",
                Integer.class))
        .isEqualTo(1);
    mockMvc
        .perform(jsonPost("/api/auth/password/reset", resetBody(second, "Newpass#12")))
        .andExpect(status().isNoContent());
  }

  @Test
  void 발송_간격과_시간당_횟수를_제한한다() throws Exception {
    saveEmailUser(EMAIL, null);
    requestToken();

    assertError(
            mockMvc.perform(jsonPost("/api/auth/password/reset-request", requestBody(EMAIL))),
            ErrorCode.RATE_LIMITED)
        .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
    for (int i = 0; i < 4; i++) {
      rateLimiter.reset(AuthRateLimits.PASSWORD_RESET_INTERVAL, EMAIL);
      mockMvc
          .perform(jsonPost("/api/auth/password/reset-request", requestBody(EMAIL)))
          .andExpect(status().isOk());
    }
    rateLimiter.reset(AuthRateLimits.PASSWORD_RESET_INTERVAL, EMAIL);
    assertError(
        mockMvc.perform(jsonPost("/api/auth/password/reset-request", requestBody(EMAIL))),
        ErrorCode.RATE_LIMITED);
  }

  @Test
  void 재설정하면_비밀번호가_바뀌고_기존_세션을_모두_종료한다() throws Exception {
    saveEmailUser(EMAIL, null);
    Cookie first = login(EMAIL, PASSWORD);
    Cookie second = login(EMAIL, PASSWORD);
    String token = requestToken();

    mockMvc
        .perform(jsonPost("/api/auth/password/reset", resetBody(token, "Newpass#12")))
        .andExpect(status().isNoContent());

    mockMvc.perform(get("/api/auth/context").cookie(first)).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/auth/context").cookie(second)).andExpect(status().isUnauthorized());
    assertThat(login(EMAIL, "Newpass#12")).isNotNull();
    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/auth/login",
                "{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}")),
        ErrorCode.INVALID_CREDENTIALS);
  }

  @Test
  void 재설정_토큰은_한_번만_쓸_수_있다() throws Exception {
    saveEmailUser(EMAIL, null);
    String token = requestToken();
    mockMvc.perform(jsonPost("/api/auth/password/reset", resetBody(token, "Newpass#12")));

    assertError(
        mockMvc.perform(jsonPost("/api/auth/password/reset", resetBody(token, "Another#123"))),
        ErrorCode.TOKEN_INVALID);
  }

  @Test
  void 만료된_토큰은_TOKEN_EXPIRED이고_없는_토큰은_TOKEN_INVALID다() throws Exception {
    saveEmailUser(EMAIL, null);
    String token = requestToken();
    jdbcTemplate.update(
        "UPDATE password_reset_token SET issued_at = ?, expires_at = ?",
        Timestamp.from(Instant.now().minus(31, ChronoUnit.MINUTES)),
        Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)));

    assertError(
        mockMvc.perform(jsonPost("/api/auth/password/reset", resetBody(token, "Newpass#12"))),
        ErrorCode.TOKEN_EXPIRED);
    assertError(
        mockMvc.perform(jsonPost("/api/auth/password/reset", resetBody("unknown", "Newpass#12"))),
        ErrorCode.TOKEN_INVALID);
  }

  @Test
  void 새_비밀번호는_코드_포인트_8자에서_12자이고_공백을_보존한다() throws Exception {
    saveEmailUser(EMAIL, null);
    String token = requestToken();

    assertFieldError(
        mockMvc.perform(jsonPost("/api/auth/password/reset", resetBody(token, "short"))),
        "newPassword",
        "TOO_SHORT");
    assertFieldError(
        mockMvc.perform(jsonPost("/api/auth/password/reset", resetBody(token, "😀".repeat(13)))),
        "newPassword",
        "TOO_LONG");
    assertFieldError(
        mockMvc.perform(jsonPost("/api/auth/password/reset", resetBody(token, "password123"))),
        "newPassword",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(jsonPost("/api/auth/password/reset", resetBody(token, " ".repeat(8)))),
        "newPassword",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(jsonPost("/api/auth/password/reset", resetBody(token, " ".repeat(12)))),
        "newPassword",
        "REQUIRED");
    mockMvc
        .perform(jsonPost("/api/auth/password/reset", resetBody(token, "  Newpass1  ")))
        .andExpect(status().isNoContent());
    assertThat(login(EMAIL, "  Newpass1  ")).isNotNull();
  }

  @Test
  void 재설정은_로그인_실패_잠금을_풀어_준다() throws Exception {
    saveEmailUser(EMAIL, null);
    jdbcTemplate.update(
        "UPDATE app_user SET failed_login_count = 5, failed_login_locked_until = now()");
    String token = requestToken();

    mockMvc.perform(jsonPost("/api/auth/password/reset", resetBody(token, "Newpass#12")));

    Integer count =
        jdbcTemplate.queryForObject("SELECT failed_login_count FROM app_user", Integer.class);
    assertThat(count).isZero();
  }

  @Test
  void 필수_입력이_비면_400이다() throws Exception {
    assertFieldError(
        mockMvc.perform(
            jsonPost("/api/auth/password/reset", "{\"token\":\"\",\"newPassword\":\"x\"}")),
        "token",
        "REQUIRED");
  }
}
