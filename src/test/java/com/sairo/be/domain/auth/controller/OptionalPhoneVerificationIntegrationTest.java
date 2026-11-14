package com.sairo.be.domain.auth.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPatch;
import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.AuthFlowSupport.KakaoStart;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

@IntegrationTest
@TestPropertySource(properties = "sairo.delivery.sms.provider=none")
class OptionalPhoneVerificationIntegrationTest extends AuthIntegrationTestBase {

  private static final String EMAIL = "agent@example.com";
  private static final String PHONE = "010-1234-5678";

  private String emailSignupBody(String emailTicket, String phone) {
    String phonePart = phone == null ? "" : ",\"phone\":" + quote(phone);
    return "{\"verifiedEmailTicket\":"
        + quote(emailTicket)
        + phonePart
        + ",\"password\":"
        + quote(PASSWORD)
        + ",\"displayName\":\"홍길동\",\"agreedTerms\":"
        + requiredTerms()
        + "}";
  }

  private Cookie kakaoSession() throws Exception {
    KakaoStart start = flow.startKakao("/api/auth/kakao/start", null);
    MvcResult result =
        mockMvc
            .perform(
                get("/api/auth/kakao/callback")
                    .cookie(start.cookie())
                    .param("code", "6001001")
                    .param("state", start.state()))
            .andReturn();
    return AuthFlowSupport.sessionCookie(result);
  }

  private String kakaoBody(String phone) {
    return "{\"displayName\":\"홍길동\",\"phone\":"
        + quote(phone)
        + ",\"agreedTerms\":"
        + requiredTerms()
        + "}";
  }

  @Test
  void 이메일_가입은_번호_입력만으로_끝나고_번호는_미인증으로_저장된다() throws Exception {
    String emailTicket = flow.sendAndVerifyEmail(EMAIL);

    mockMvc
        .perform(jsonPost("/api/auth/signup/email", emailSignupBody(emailTicket, "01012345678")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.accessCondition").value("PRE_VERIFICATION"));

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT phone, phone_verified_at, email_verified_at FROM app_user");
    assertThat(row.get("phone")).isEqualTo(PHONE);
    assertThat(row.get("phone_verified_at")).isNull();
    assertThat(row.get("email_verified_at")).isNotNull();
    assertThat(smsSender.sent()).isEmpty();
  }

  @Test
  void 번호가_없거나_형식이_틀리면_이메일_가입은_400이다() throws Exception {
    String emailTicket = flow.sendAndVerifyEmail(EMAIL);

    assertFieldError(
        mockMvc.perform(jsonPost("/api/auth/signup/email", emailSignupBody(emailTicket, null))),
        "phone",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(jsonPost("/api/auth/signup/email", emailSignupBody(emailTicket, "123"))),
        "phone",
        "INVALID_FORMAT");
    assertThat(appUserRepository.count()).isZero();
  }

  @Test
  void 미인증_번호는_다른_계정과_겹쳐도_가입할_수_있다() throws Exception {
    saveEmailUser("other@example.com", PHONE);
    String emailTicket = flow.sendAndVerifyEmail(EMAIL);

    mockMvc
        .perform(jsonPost("/api/auth/signup/email", emailSignupBody(emailTicket, PHONE)))
        .andExpect(status().isCreated());

    assertThat(appUserRepository.count()).isEqualTo(2);
  }

  @Test
  void 카카오_가입은_번호_입력만으로_끝나고_다시_완료할_수_없다() throws Exception {
    Cookie session = kakaoSession();

    MvcResult signup =
        mockMvc
            .perform(jsonPost("/api/auth/signup/kakao", kakaoBody("010-1234-5678"), session))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessCondition").value("PRE_VERIFICATION"))
            .andReturn();
    Cookie renewed = AuthFlowSupport.sessionCookie(signup);

    Map<String, Object> row =
        jdbcTemplate.queryForMap("SELECT phone, phone_verified_at FROM app_user");
    assertThat(row.get("phone")).isEqualTo(PHONE);
    assertThat(row.get("phone_verified_at")).isNull();
    assertError(
        mockMvc.perform(jsonPost("/api/auth/signup/kakao", kakaoBody("010-1234-5678"), renewed)),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 휴대폰_변경은_티켓_없이_번호만_보내면_미인증으로_저장한다() throws Exception {
    saveEmailUser(EMAIL, PHONE);
    Cookie session = login(EMAIL, PASSWORD);

    mockMvc
        .perform(jsonPatch("/api/me/contact", "{\"phone\":\"01099990000\"}", session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.field").value("phone"))
        .andExpect(jsonPath("$.value").value("010-9999-0000"));

    Map<String, Object> row =
        jdbcTemplate.queryForMap("SELECT phone, phone_verified_at FROM app_user");
    assertThat(row.get("phone")).isEqualTo("010-9999-0000");
    assertThat(row.get("phone_verified_at")).isNull();
    mockMvc
        .perform(get("/api/me").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.phoneVerified").value(false));
    assertFieldError(
        mockMvc.perform(jsonPatch("/api/me/contact", "{\"phone\":\"12\"}", session)),
        "phone",
        "INVALID_FORMAT");
  }

  @Test
  void 이메일_변경은_본인확인_설정과_관계없이_티켓이_필요하다() throws Exception {
    saveEmailUser(EMAIL, PHONE);
    Cookie session = login(EMAIL, PASSWORD);

    assertFieldError(
        mockMvc.perform(jsonPatch("/api/me/contact", "{\"email\":\"new@example.com\"}", session)),
        "verifiedTicket",
        "REQUIRED");
  }
}
