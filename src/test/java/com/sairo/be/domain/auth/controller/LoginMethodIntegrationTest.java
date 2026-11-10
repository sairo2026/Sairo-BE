package com.sairo.be.domain.auth.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StepUpState;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.AuthFlowSupport.KakaoStart;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.AppUserBuilder;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class LoginMethodIntegrationTest extends AuthIntegrationTestBase {

  private AppUser kakaoUser(String key) {
    return appUserRepository.saveAndFlush(AppUserBuilder.anAppUser().kakaoProviderKey(key).build());
  }

  private ResultActions unlink(String method, Cookie session) throws Exception {
    return mockMvc.perform(
        AuthFlowSupport.withCsrf(delete("/api/me/login-methods/" + method).cookie(session)));
  }

  private String linkBody(String ticket, String password) {
    return "{\"verifiedEmailTicket\":" + quote(ticket) + ",\"password\":" + quote(password) + "}";
  }

  private String emailTicketFor(Cookie session, String email) throws Exception {
    mockMvc
        .perform(
            jsonPost(
                "/api/me/login-methods/email/verification",
                "{\"email\":\"" + email + "\"}",
                session))
        .andExpect(status().isAccepted());
    String token = flow.lastMailToken(email);
    MvcResult verified =
        mockMvc
            .perform(
                jsonPost(
                    "/api/me/login-methods/email/verify", "{\"token\":\"" + token + "\"}", session))
            .andExpect(status().isOk())
            .andReturn();
    return AuthFlowSupport.json(verified).get("verifiedEmailTicket").asString();
  }

  @Test
  void 카카오_계정에_이메일을_연결하면_이메일과_비밀번호_해시를_저장하고_재인증을_소비한다() throws Exception {
    AppUser user = kakaoUser("8001001");
    Cookie session = kakaoSessionFor(user);
    String ticket = emailTicketFor(session, "link@example.com");
    grantStepUp(session);

    mockMvc
        .perform(jsonPost("/api/me/login-methods/email", linkBody(ticket, PASSWORD), session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.method").value("EMAIL"))
        .andExpect(jsonPath("$.linked").value(true));

    Map<String, Object> row =
        jdbcTemplate.queryForMap("SELECT email, password_hash, email_verified_at FROM app_user");
    assertThat(row.get("email")).isEqualTo("link@example.com");
    assertThat((String) row.get("password_hash")).startsWith("$2");
    assertThat(row.get("email_verified_at")).isNotNull();
    assertThat(sessionAttribute(session, StepUpState.STEP_UP_UNTIL_ATTRIBUTE)).isNull();
    mockMvc
        .perform(get("/api/me").cookie(session))
        .andExpect(jsonPath("$.loginMethods.length()").value(2));
    assertThat(login("link@example.com", PASSWORD)).isNotNull();
  }

  @Test
  void 재인증이_없으면_이메일_연결은_401_STEP_UP_REQUIRED다() throws Exception {
    Cookie session = kakaoSessionFor(kakaoUser("8001002"));
    String ticket = emailTicketFor(session, "link@example.com");

    assertError(
        mockMvc.perform(
            jsonPost("/api/me/login-methods/email", linkBody(ticket, PASSWORD), session)),
        ErrorCode.STEP_UP_REQUIRED);
  }

  @Test
  void 재인증은_한_번만_쓸_수_있다() throws Exception {
    Cookie session = kakaoSessionFor(kakaoUser("8001003"));
    String ticket = emailTicketFor(session, "link@example.com");
    grantStepUp(session);
    mockMvc.perform(jsonPost("/api/me/login-methods/email", linkBody(ticket, PASSWORD), session));

    assertError(unlink("email", session), ErrorCode.STEP_UP_REQUIRED);
  }

  @Test
  void 이메일_연결_비밀번호는_코드_포인트_8자에서_12자_정책을_따른다() throws Exception {
    Cookie session = kakaoSessionFor(kakaoUser("8001004"));
    String ticket = emailTicketFor(session, "link@example.com");
    grantStepUp(session);

    assertFieldError(
        mockMvc.perform(
            jsonPost("/api/me/login-methods/email", linkBody(ticket, "short"), session)),
        "password",
        "TOO_SHORT");
    assertFieldError(
        mockMvc.perform(
            jsonPost("/api/me/login-methods/email", linkBody(ticket, "😀".repeat(13)), session)),
        "password",
        "TOO_LONG");
    mockMvc
        .perform(jsonPost("/api/me/login-methods/email", linkBody(ticket, "😀".repeat(8)), session))
        .andExpect(status().isOk());
  }

  @Test
  void 이미_가입된_이메일은_인증_링크_발송이_409다() throws Exception {
    saveEmailUser("taken@example.com", null);
    Cookie session = kakaoSessionFor(kakaoUser("8001005"));

    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/me/login-methods/email/verification",
                "{\"email\":\"taken@example.com\"}",
                session)),
        ErrorCode.EMAIL_ALREADY_REGISTERED);
  }

  @Test
  void 이미_이메일_로그인이_있으면_연결_인증_링크_발송이_409다() throws Exception {
    saveEmailUser("agent@example.com", null);
    Cookie session = login("agent@example.com", PASSWORD);

    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/me/login-methods/email/verification",
                "{\"email\":\"new@example.com\"}",
                session)),
        ErrorCode.EMAIL_ALREADY_REGISTERED);
  }

  @Test
  void 다른_회원의_티켓으로는_연결할_수_없다() throws Exception {
    Cookie owner = kakaoSessionFor(kakaoUser("8001006"));
    Cookie other = kakaoSessionFor(kakaoUser("8001007"));
    String ticket = emailTicketFor(owner, "link@example.com");
    grantStepUp(other);

    assertError(
        mockMvc.perform(jsonPost("/api/me/login-methods/email", linkBody(ticket, PASSWORD), other)),
        ErrorCode.TOKEN_INVALID);
  }

  @Test
  void 가입_목적_토큰은_연결_인증에_쓸_수_없다() throws Exception {
    Cookie session = kakaoSessionFor(kakaoUser("8001008"));
    mockMvc.perform(
        jsonPost("/api/auth/signup/email/verification", "{\"email\":\"signup@example.com\"}"));
    String token = flow.lastMailToken("signup@example.com");

    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/me/login-methods/email/verify", "{\"token\":\"" + token + "\"}", session)),
        ErrorCode.TOKEN_INVALID);
  }

  @Test
  void 이메일_계정에_카카오를_연결한다() throws Exception {
    saveEmailUser("agent@example.com", null);
    Cookie session = login("agent@example.com", PASSWORD);
    grantStepUp(session);
    KakaoStart start = flow.startKakao("/api/me/login-methods/kakao/start", session);
    assertThat(start.location()).contains("flow=LINK");

    mockMvc
        .perform(
            jsonPost(
                "/api/me/login-methods/kakao",
                "{\"code\":\"8002001\",\"state\":\"" + start.state() + "\"}",
                session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.method").value("KAKAO"))
        .andExpect(jsonPath("$.linked").value(true));

    assertThat(jdbcTemplate.queryForObject("SELECT kakao_provider_key FROM app_user", String.class))
        .isEqualTo("8002001");
    assertThat(sessionAttribute(session, StepUpState.STEP_UP_UNTIL_ATTRIBUTE)).isNull();
  }

  @Test
  void 카카오_연결_시작은_재인증이_없으면_401이다() throws Exception {
    saveEmailUser("agent@example.com", null);
    Cookie session = login("agent@example.com", PASSWORD);

    assertError(
        mockMvc.perform(get("/api/me/login-methods/kakao/start").cookie(session)),
        ErrorCode.STEP_UP_REQUIRED);
  }

  @Test
  void 이미_다른_계정에_연결된_카카오는_409다() throws Exception {
    kakaoUser("8002002");
    saveEmailUser("agent@example.com", null);
    Cookie session = login("agent@example.com", PASSWORD);
    grantStepUp(session);
    KakaoStart start = flow.startKakao("/api/me/login-methods/kakao/start", session);

    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/me/login-methods/kakao",
                "{\"code\":\"8002002\",\"state\":\"" + start.state() + "\"}",
                session)),
        ErrorCode.KAKAO_ALREADY_LINKED);
  }

  @Test
  void 연결_콜백의_state가_틀리면_400이다() throws Exception {
    saveEmailUser("agent@example.com", null);
    Cookie session = login("agent@example.com", PASSWORD);
    grantStepUp(session);

    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/me/login-methods/kakao",
                "{\"code\":\"8002003\",\"state\":\"wrong\"}",
                session)),
        ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 카카오를_해제하면_연결_끊기_작업을_암호화해_등록하고_재인증을_소비한다() throws Exception {
    AppUser user = kakaoUser("8003001");
    Cookie session = kakaoSessionFor(user);
    jdbcTemplate.update(
        "UPDATE app_user SET email = 'agent@example.com', password_hash = ?,"
            + " email_verified_at = now()",
        "$2a$04$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ012345");
    grantStepUp(session);

    unlink("kakao", session).andExpect(status().isNoContent());

    Map<String, Object> event =
        jdbcTemplate.queryForMap(
            "SELECT direction, status, target_key_encrypted, kakao_key_hmac, next_attempt_at,"
                + " user_id FROM legal_kakao_unlink_event");
    assertThat(event.get("direction")).isEqualTo("OUTBOUND_CALL");
    assertThat(event.get("status")).isEqualTo("PENDING");
    assertThat((String) event.get("target_key_encrypted")).isNotBlank().doesNotContain("8003001");
    assertThat(event.get("next_attempt_at")).isNotNull();
    assertThat(event.get("user_id")).isEqualTo(user.getId());
    assertThat(jdbcTemplate.queryForObject("SELECT kakao_provider_key FROM app_user", String.class))
        .isNull();
    assertThat(sessionAttribute(session, StepUpState.STEP_UP_UNTIL_ATTRIBUTE)).isNull();
  }

  @Test
  void 이메일을_해제하면_이메일과_비밀번호를_지운다() throws Exception {
    AppUser user = kakaoUser("8003002");
    Cookie session = kakaoSessionFor(user);
    jdbcTemplate.update(
        "UPDATE app_user SET email = 'agent@example.com', password_hash = 'x',"
            + " email_verified_at = now()");
    grantStepUp(session);

    unlink("email", session).andExpect(status().isNoContent());

    Map<String, Object> row =
        jdbcTemplate.queryForMap("SELECT email, password_hash, email_verified_at FROM app_user");
    assertThat(row.values()).containsOnlyNulls();
  }

  @Test
  void 마지막_남은_로그인수단은_해제할_수_없다() throws Exception {
    saveEmailUser("agent@example.com", null);
    Cookie session = login("agent@example.com", PASSWORD);
    grantStepUp(session);
    assertError(unlink("email", session), ErrorCode.LAST_LOGIN_METHOD);

    AppUser user = kakaoUser("8003003");
    Cookie kakaoSession = kakaoSessionFor(user);
    grantStepUp(kakaoSession);
    assertError(unlink("kakao", kakaoSession), ErrorCode.LAST_LOGIN_METHOD);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM legal_kakao_unlink_event", Integer.class))
        .isZero();
  }

  @Test
  void 해제는_재인증이_없으면_401이다() throws Exception {
    AppUser user = kakaoUser("8003004");
    Cookie session = kakaoSessionFor(user);
    jdbcTemplate.update(
        "UPDATE app_user SET email = 'agent@example.com', password_hash = 'x',"
            + " email_verified_at = now()");

    assertError(unlink("kakao", session), ErrorCode.STEP_UP_REQUIRED);
  }

  @Test
  void 알_수_없는_수단은_400이고_연결되지_않은_수단은_404다() throws Exception {
    saveEmailUser("agent@example.com", null);
    Cookie session = login("agent@example.com", PASSWORD);
    grantStepUp(session);

    assertError(unlink("sms", session), ErrorCode.INVALID_REQUEST);
    assertError(unlink("kakao", session), ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 로그인_없이는_401이다() throws Exception {
    assertError(
        mockMvc.perform(AuthFlowSupport.withCsrf(delete("/api/me/login-methods/email"))),
        ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(jsonPost("/api/me/login-methods/email", "{}")), ErrorCode.UNAUTHENTICATED);
  }
}
