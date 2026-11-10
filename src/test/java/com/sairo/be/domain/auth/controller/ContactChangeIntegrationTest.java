package com.sairo.be.domain.auth.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPatch;
import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.SignedTicketService;
import com.sairo.be.global.security.StepUpState;
import com.sairo.be.global.security.TicketType;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.AppUserBuilder;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class ContactChangeIntegrationTest extends AuthIntegrationTestBase {

  private static final String NEW_EMAIL = "new@example.com";

  @Autowired private SignedTicketService ticketService;

  private AppUser user;
  private Cookie session;

  @BeforeEach
  void createUser() throws Exception {
    user = saveEmailUser("agent@example.com", "010-1234-5678");
    session = login("agent@example.com", PASSWORD);
  }

  private String newEmailTicket() throws Exception {
    mockMvc
        .perform(
            jsonPost(
                "/api/me/contact/email/verification", "{\"email\":\"" + NEW_EMAIL + "\"}", session))
        .andExpect(status().isAccepted());
    String token = flow.lastMailToken(NEW_EMAIL);
    var result =
        mockMvc
            .perform(
                jsonPost("/api/me/contact/email/verify", "{\"token\":\"" + token + "\"}", session))
            .andExpect(status().isOk())
            .andReturn();
    return AuthFlowSupport.json(result).get("verifiedEmailTicket").asString();
  }

  private String changeEmailBody(String email, String ticket) {
    return "{\"email\":" + quote(email) + ",\"verifiedTicket\":" + quote(ticket) + "}";
  }

  private String phoneTicket(String phone, Cookie cookie) throws Exception {
    mockMvc
        .perform(
            jsonPost(
                "/api/auth/phone-verification",
                "{\"phone\":\"" + phone + "\",\"purpose\":\"CHANGE\"}",
                cookie))
        .andExpect(status().isAccepted());
    var result =
        mockMvc
            .perform(
                jsonPost(
                    "/api/auth/phone-verification/verify",
                    "{\"phone\":\""
                        + phone
                        + "\",\"purpose\":\"CHANGE\",\"code\":\""
                        + flow.lastSmsCode(phone)
                        + "\"}",
                    cookie))
            .andExpect(status().isOk())
            .andReturn();
    return AuthFlowSupport.json(result).get("verifiedPhoneTicket").asString();
  }

  @Test
  void 이메일_변경_인증_링크_발송은_재인증이_필요하다() throws Exception {
    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/me/contact/email/verification",
                "{\"email\":\"" + NEW_EMAIL + "\"}",
                session)),
        ErrorCode.STEP_UP_REQUIRED);
  }

  @Test
  void 이메일을_바꾸면_새_주소로_갱신하고_비밀번호_해시는_유지하며_이전_주소에_알린다() throws Exception {
    grantStepUp(session);
    String ticket = newEmailTicket();
    String hashBefore =
        jdbcTemplate.queryForObject("SELECT password_hash FROM app_user", String.class);

    mockMvc
        .perform(jsonPatch("/api/me/contact", changeEmailBody(NEW_EMAIL, ticket), session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.field").value("email"))
        .andExpect(jsonPath("$.value").value(NEW_EMAIL))
        .andExpect(jsonPath("$.verifiedAt").isNotEmpty());

    Map<String, Object> row =
        jdbcTemplate.queryForMap("SELECT email, password_hash, email_verified_at FROM app_user");
    assertThat(row.get("email")).isEqualTo(NEW_EMAIL);
    assertThat(row.get("password_hash")).isEqualTo(hashBefore);
    assertThat(row.get("email_verified_at")).isNotNull();
    assertThat(mailSender.sent().stream().anyMatch(m -> m.to().equals("agent@example.com")))
        .isTrue();
    assertThat(sessionAttribute(session, StepUpState.STEP_UP_UNTIL_ATTRIBUTE)).isNull();
    assertThat(login(NEW_EMAIL, PASSWORD)).isNotNull();
  }

  @Test
  void 재인증이_없으면_이메일_변경은_401이다() throws Exception {
    grantStepUp(session);
    String ticket = newEmailTicket();
    setSessionAttribute(
        session, StepUpState.STEP_UP_UNTIL_ATTRIBUTE, java.time.Instant.now().minusSeconds(1));

    assertError(
        mockMvc.perform(jsonPatch("/api/me/contact", changeEmailBody(NEW_EMAIL, ticket), session)),
        ErrorCode.STEP_UP_REQUIRED);
    assertError(
        mockMvc.perform(jsonPost("/api/me/contact/email/verify", "{\"token\":\"x\"}", session)),
        ErrorCode.STEP_UP_REQUIRED);
  }

  @Test
  void 이미_가입된_주소로는_변경할_수_없다() throws Exception {
    saveEmailUser(NEW_EMAIL, null);
    grantStepUp(session);

    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/me/contact/email/verification",
                "{\"email\":\"" + NEW_EMAIL + "\"}",
                session)),
        ErrorCode.EMAIL_ALREADY_REGISTERED);
  }

  @Test
  void 티켓의_주소와_다른_주소로는_변경할_수_없다() throws Exception {
    grantStepUp(session);
    String ticket = newEmailTicket();

    assertError(
        mockMvc.perform(
            jsonPatch("/api/me/contact", changeEmailBody("other@example.com", ticket), session)),
        ErrorCode.TOKEN_INVALID);
  }

  @Test
  void 티켓은_한_번만_쓸_수_있다() throws Exception {
    grantStepUp(session);
    String ticket = newEmailTicket();
    mockMvc.perform(jsonPatch("/api/me/contact", changeEmailBody(NEW_EMAIL, ticket), session));
    grantStepUp(session);

    assertError(
        mockMvc.perform(jsonPatch("/api/me/contact", changeEmailBody(NEW_EMAIL, ticket), session)),
        ErrorCode.TOKEN_INVALID);
  }

  @Test
  void 만료된_티켓은_422다() throws Exception {
    grantStepUp(session);
    String expired =
        ticketService
            .issue(TicketType.EMAIL, NEW_EMAIL, "CHANGE", user.getId(), Duration.ofSeconds(-1))
            .token();

    assertError(
        mockMvc.perform(jsonPatch("/api/me/contact", changeEmailBody(NEW_EMAIL, expired), session)),
        ErrorCode.TOKEN_EXPIRED);
  }

  @Test
  void 카카오_전용_계정은_이메일을_바꿀_수_없다() throws Exception {
    AppUser kakao =
        appUserRepository.saveAndFlush(
            AppUserBuilder.anAppUser().kakaoProviderKey("5551001").build());
    Cookie kakaoSession = kakaoSessionFor(kakao);
    grantStepUp(kakaoSession);

    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/me/contact/email/verification",
                "{\"email\":\"" + NEW_EMAIL + "\"}",
                kakaoSession)),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void email과_phone을_함께_보내거나_둘_다_없으면_400이다() throws Exception {
    assertFieldError(
        mockMvc.perform(
            jsonPatch(
                "/api/me/contact",
                "{\"email\":\"a@example.com\",\"phone\":\"010-2222-3333\",\"verifiedTicket\":\"t\"}",
                session)),
        "email",
        "INVALID_VALUE");
    assertFieldError(
        mockMvc.perform(jsonPatch("/api/me/contact", "{\"verifiedTicket\":\"t\"}", session)),
        "email",
        "INVALID_VALUE");
    assertFieldError(
        mockMvc.perform(jsonPatch("/api/me/contact", "{\"email\":\"a@example.com\"}", session)),
        "verifiedTicket",
        "REQUIRED");
  }

  @Test
  void 휴대폰은_재인증_없이_새_번호_티켓으로_바꾼다() throws Exception {
    String ticket = phoneTicket("010-9999-0000", session);

    mockMvc
        .perform(
            jsonPatch(
                "/api/me/contact",
                "{\"phone\":\"010-9999-0000\",\"verifiedTicket\":" + quote(ticket) + "}",
                session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.field").value("phone"))
        .andExpect(jsonPath("$.value").value("010-9999-0000"));

    Map<String, Object> row =
        jdbcTemplate.queryForMap("SELECT phone, phone_verified_at FROM app_user");
    assertThat(row.get("phone")).isEqualTo("010-9999-0000");
    assertThat(row.get("phone_verified_at")).isNotNull();
  }

  @Test
  void 이미_다른_계정에_본인확인된_번호는_409다() throws Exception {
    saveEmailUser("other@example.com", "010-9999-0000");
    String ticket = phoneTicket("010-9999-0000", session);

    assertError(
        mockMvc.perform(
            jsonPatch(
                "/api/me/contact",
                "{\"phone\":\"010-9999-0000\",\"verifiedTicket\":" + quote(ticket) + "}",
                session)),
        ErrorCode.PHONE_ALREADY_REGISTERED);
  }

  @Test
  void 티켓의_번호와_다른_번호나_가입_목적_티켓은_쓸_수_없다() throws Exception {
    String ticket = phoneTicket("010-9999-0000", session);
    assertError(
        mockMvc.perform(
            jsonPatch(
                "/api/me/contact",
                "{\"phone\":\"010-8888-0000\",\"verifiedTicket\":" + quote(ticket) + "}",
                session)),
        ErrorCode.TOKEN_INVALID);

    String signupTicket = flow.sendAndVerifyPhone("010-7777-0000");
    assertError(
        mockMvc.perform(
            jsonPatch(
                "/api/me/contact",
                "{\"phone\":\"010-7777-0000\",\"verifiedTicket\":" + quote(signupTicket) + "}",
                session)),
        ErrorCode.TOKEN_INVALID);
  }

  @Test
  void 다른_회원이_받은_휴대폰_티켓은_쓸_수_없다() throws Exception {
    AppUser other = saveEmailUser("other@example.com", "010-0000-2222");
    Cookie otherSession = login("other@example.com", PASSWORD);
    String ticket = phoneTicket("010-9999-0000", otherSession);

    assertError(
        mockMvc.perform(
            jsonPatch(
                "/api/me/contact",
                "{\"phone\":\"010-9999-0000\",\"verifiedTicket\":" + quote(ticket) + "}",
                session)),
        ErrorCode.TOKEN_INVALID);
    assertThat(other.getId()).isNotEqualTo(user.getId());
  }

  @Test
  void 로그인_없이는_401이다() throws Exception {
    assertError(
        mockMvc.perform(jsonPatch("/api/me/contact", "{\"verifiedTicket\":\"t\"}")),
        ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(jsonPost("/api/me/contact/email/verification", "{\"email\":\"a@b.co\"}")),
        ErrorCode.UNAUTHENTICATED);
  }
}
