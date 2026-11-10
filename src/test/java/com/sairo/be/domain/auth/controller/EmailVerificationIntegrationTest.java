package com.sairo.be.domain.auth.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.auth.service.AuthRateLimits;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.support.RequestRateLimiter;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class EmailVerificationIntegrationTest extends AuthIntegrationTestBase {

  private static final String EMAIL = "agent@example.com";

  @Autowired private RequestRateLimiter rateLimiter;

  private void send(String email) throws Exception {
    mockMvc.perform(
        jsonPost("/api/auth/signup/email/verification", "{\"email\":\"" + email + "\"}"));
  }

  @Test
  void 인증_메일_발송은_202와_재발송_대기를_돌려주고_토큰은_해시로_저장한다() throws Exception {
    mockMvc
        .perform(jsonPost("/api/auth/signup/email/verification", "{\"email\":\"" + EMAIL + "\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.accepted").value(true))
        .andExpect(jsonPath("$.resendAfterSeconds").value(60));

    String token = flow.lastMailToken(EMAIL);
    String storedHash =
        jdbcTemplate.queryForObject(
            "SELECT token_hash FROM email_verification_token WHERE email = ?", String.class, EMAIL);
    assertThat(storedHash).hasSize(64).isNotEqualTo(token);
    Integer minutes =
        jdbcTemplate.queryForObject(
            "SELECT (extract(epoch FROM expires_at - issued_at) / 60)::int"
                + " FROM email_verification_token WHERE email = ?",
            Integer.class,
            EMAIL);
    assertThat(minutes).isEqualTo(30);
  }

  @Test
  void 이메일은_소문자로_정규화해_저장한다() throws Exception {
    send("Agent@Example.COM");

    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM email_verification_token WHERE email = ?", Integer.class, EMAIL);
    assertThat(count).isEqualTo(1);
  }

  @Test
  void 이메일_형식이_아니면_INVALID_REQUEST다() throws Exception {
    assertFieldError(
        mockMvc.perform(
            jsonPost("/api/auth/signup/email/verification", "{\"email\":\"not-mail\"}")),
        "email",
        "INVALID_FORMAT");
  }

  @Test
  void 이미_가입된_이메일은_409다() throws Exception {
    saveEmailUser(EMAIL, null);

    assertError(
        mockMvc.perform(
            jsonPost("/api/auth/signup/email/verification", "{\"email\":\"" + EMAIL + "\"}")),
        ErrorCode.EMAIL_ALREADY_REGISTERED);
  }

  @Test
  void 발송_간격_60초_안의_재요청은_429이고_대기_시간을_알린다() throws Exception {
    send(EMAIL);

    assertError(
            mockMvc.perform(
                jsonPost("/api/auth/signup/email/verification", "{\"email\":\"" + EMAIL + "\"}")),
            ErrorCode.RATE_LIMITED)
        .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
  }

  @Test
  void 같은_주소는_시간당_5회까지_허용하고_6번째는_429다() throws Exception {
    for (int i = 0; i < 5; i++) {
      rateLimiter.reset(AuthRateLimits.EMAIL_SEND_INTERVAL, EMAIL);
      mockMvc
          .perform(jsonPost("/api/auth/signup/email/verification", "{\"email\":\"" + EMAIL + "\"}"))
          .andExpect(status().isAccepted());
    }
    rateLimiter.reset(AuthRateLimits.EMAIL_SEND_INTERVAL, EMAIL);

    assertError(
        mockMvc.perform(
            jsonPost("/api/auth/signup/email/verification", "{\"email\":\"" + EMAIL + "\"}")),
        ErrorCode.RATE_LIMITED);
  }

  @Test
  void 재발송하면_이전_토큰은_무효가_되고_새_토큰만_쓸_수_있다() throws Exception {
    send(EMAIL);
    String first = flow.lastMailToken(EMAIL);
    rateLimiter.reset(AuthRateLimits.EMAIL_SEND_INTERVAL, EMAIL);
    send(EMAIL);
    String second = flow.lastMailToken(EMAIL);

    assertThat(second).isNotEqualTo(first);
    assertError(
        mockMvc.perform(jsonPost("/api/auth/signup/email/verify", "{\"token\":\"" + first + "\"}")),
        ErrorCode.TOKEN_INVALID);
    mockMvc
        .perform(jsonPost("/api/auth/signup/email/verify", "{\"token\":\"" + second + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verifiedEmailTicket").isNotEmpty());
    Integer active =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM email_verification_token"
                + " WHERE consumed_at IS NULL AND superseded_at IS NULL",
            Integer.class);
    assertThat(active).isZero();
  }

  @Test
  void 같은_토큰을_두_번_쓰면_TOKEN_INVALID다() throws Exception {
    send(EMAIL);
    String token = flow.lastMailToken(EMAIL);
    mockMvc.perform(jsonPost("/api/auth/signup/email/verify", "{\"token\":\"" + token + "\"}"));

    assertError(
        mockMvc.perform(jsonPost("/api/auth/signup/email/verify", "{\"token\":\"" + token + "\"}")),
        ErrorCode.TOKEN_INVALID);
  }

  @Test
  void 만료된_토큰은_TOKEN_EXPIRED다() throws Exception {
    send(EMAIL);
    String token = flow.lastMailToken(EMAIL);
    Timestamp past = Timestamp.from(Instant.now().minus(31, ChronoUnit.MINUTES));
    jdbcTemplate.update(
        "UPDATE email_verification_token SET issued_at = ?, expires_at = ?::timestamptz + interval '30 minutes'",
        past,
        past);

    assertError(
        mockMvc.perform(jsonPost("/api/auth/signup/email/verify", "{\"token\":\"" + token + "\"}")),
        ErrorCode.TOKEN_EXPIRED);
  }

  @Test
  void 없는_토큰은_TOKEN_INVALID다() throws Exception {
    assertError(
        mockMvc.perform(jsonPost("/api/auth/signup/email/verify", "{\"token\":\"unknown-token\"}")),
        ErrorCode.TOKEN_INVALID);
  }

  @Test
  void 토큰이_비어_있으면_INVALID_REQUEST다() throws Exception {
    assertFieldError(
        mockMvc.perform(jsonPost("/api/auth/signup/email/verify", "{\"token\":\"\"}")),
        "token",
        "REQUIRED");
  }

  @Test
  void 가입_목적_토큰은_티켓을_돌려주고_휴대폰_티켓_자리에는_쓸_수_없다() throws Exception {
    String emailTicket = flow.sendAndVerifyEmail(EMAIL);

    assertThat(emailTicket).contains("~");
    String phoneTicket = flow.sendAndVerifyPhone("010-1234-5678");
    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/auth/signup/email",
                signupBody(phoneTicket, phoneTicket, PASSWORD, "홍길동", requiredTerms()))),
        ErrorCode.TOKEN_INVALID);
  }

  @Test
  void 인증_확인_호출은_IP당_분당_30회로_제한한다() throws Exception {
    for (int i = 0; i < 30; i++) {
      mockMvc.perform(jsonPost("/api/auth/signup/email/verify", "{\"token\":\"unknown-token\"}"));
    }

    assertError(
        mockMvc.perform(jsonPost("/api/auth/signup/email/verify", "{\"token\":\"unknown-token\"}")),
        ErrorCode.RATE_LIMITED);
  }
}
