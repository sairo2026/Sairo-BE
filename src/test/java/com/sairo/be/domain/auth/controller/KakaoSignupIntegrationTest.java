package com.sairo.be.domain.auth.controller;

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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

@IntegrationTest
class KakaoSignupIntegrationTest extends AuthIntegrationTestBase {

  private static final String PHONE = "010-1234-5678";

  private Cookie session;

  @BeforeEach
  void kakaoLogin() throws Exception {
    KakaoStart start = flow.startKakao("/api/auth/kakao/start", null);
    MvcResult result =
        mockMvc
            .perform(
                get("/api/auth/kakao/callback")
                    .cookie(start.cookie())
                    .param("code", "6001001")
                    .param("state", start.state()))
            .andReturn();
    session = AuthFlowSupport.sessionCookie(result);
  }

  private String body(String name, String phoneTicket, String referenceEmail, String terms) {
    String reference =
        referenceEmail == null ? "" : ",\"kakaoReferenceEmail\":" + quote(referenceEmail);
    return "{\"displayName\":"
        + quote(name)
        + ",\"verifiedPhoneTicket\":"
        + quote(phoneTicket)
        + reference
        + ",\"agreedTerms\":"
        + terms
        + "}";
  }

  @Test
  void 추가정보를_입력하면_가입을_완료하고_접근_판정을_돌려준다() throws Exception {
    String ticket = flow.sendAndVerifyPhone(PHONE);

    MvcResult result =
        mockMvc
            .perform(
                jsonPost(
                    "/api/auth/signup/kakao",
                    body("홍길동", ticket, "ref@example.com", requiredTerms()),
                    session))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessCondition").value("PRE_VERIFICATION"))
            .andReturn();

    Map<String, Object> user =
        jdbcTemplate.queryForMap(
            "SELECT name, phone, phone_verified_at, email, kakao_reference_email FROM app_user");
    assertThat(user.get("name")).isEqualTo("홍길동");
    assertThat(user.get("phone")).isEqualTo(PHONE);
    assertThat(user.get("phone_verified_at")).isNotNull();
    assertThat(user.get("email")).isNull();
    assertThat(user.get("kakao_reference_email")).isEqualTo("ref@example.com");
    assertThat(
            jdbcTemplate.queryForObject("SELECT consent_path FROM terms_agreement", String.class))
        .isEqualTo("SIGNUP_KAKAO");
    Cookie renewed = AuthFlowSupport.sessionCookie(result);
    assertThat(renewed).isNotNull();
    mockMvc
        .perform(get("/api/me").cookie(renewed))
        .andExpect(jsonPath("$.displayName").value("홍길동"))
        .andExpect(jsonPath("$.loginMethods[0]").value("KAKAO"));
  }

  @Test
  void 참고_이메일은_인증하지_않고_로그인_이메일로_복사하지_않으며_기존_계정과_병합하지_않는다() throws Exception {
    saveEmailUser("same@example.com", "010-9999-0000");
    String ticket = flow.sendAndVerifyPhone(PHONE);

    mockMvc
        .perform(
            jsonPost(
                "/api/auth/signup/kakao",
                body("홍길동", ticket, "Same@Example.com", requiredTerms()),
                session))
        .andExpect(status().isCreated());

    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user", Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT email FROM app_user WHERE kakao_provider_key = '6001001'", String.class))
        .isNull();
  }

  @Test
  void 약관_버전이_다르면_409이고_저장하지_않으며_티켓은_다시_쓸_수_있다() throws Exception {
    String ticket = flow.sendAndVerifyPhone(PHONE);
    String old = "[{\"termsCode\":\"TOS\",\"version\":\"2025-01-01\"}]";

    assertError(
            mockMvc.perform(
                jsonPost("/api/auth/signup/kakao", body("홍길동", ticket, null, old), session)),
            ErrorCode.TERMS_VERSION_CHANGED)
        .andExpect(jsonPath("$.changedItems[0].currentVersion").value("2026-09-01"));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT phone_verified_at IS NULL FROM app_user", Boolean.class))
        .isTrue();

    mockMvc
        .perform(
            jsonPost("/api/auth/signup/kakao", body("홍길동", ticket, null, requiredTerms()), session))
        .andExpect(status().isCreated());
  }

  @Test
  void 이메일이_없는_계정의_마케팅_동의는_422다() throws Exception {
    String ticket = flow.sendAndVerifyPhone(PHONE);

    assertError(
        mockMvc.perform(
            jsonPost("/api/auth/signup/kakao", body("홍길동", ticket, null, allTerms()), session)),
        ErrorCode.MARKETING_EMAIL_REQUIRED);
  }

  @Test
  void 무효이거나_만료된_휴대폰_티켓은_422다() throws Exception {
    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/auth/signup/kakao", body("홍길동", "forged", null, requiredTerms()), session)),
        ErrorCode.TOKEN_INVALID);
  }

  @Test
  void 이미_본인확인된_번호는_409다() throws Exception {
    saveEmailUser("other@example.com", PHONE);
    String ticket = flow.sendAndVerifyPhone(PHONE);

    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/auth/signup/kakao", body("홍길동", ticket, null, requiredTerms()), session)),
        ErrorCode.PHONE_ALREADY_REGISTERED);
  }

  @Test
  void 이미_가입을_마친_계정이_다시_호출하면_409다() throws Exception {
    String ticket = flow.sendAndVerifyPhone(PHONE);
    MvcResult first =
        mockMvc
            .perform(
                jsonPost(
                    "/api/auth/signup/kakao", body("홍길동", ticket, null, requiredTerms()), session))
            .andReturn();
    Cookie renewed = AuthFlowSupport.sessionCookie(first);
    String second = flow.sendAndVerifyPhone("010-2222-3333");

    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/auth/signup/kakao", body("홍길동", second, null, requiredTerms()), renewed)),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 이름_길이와_참고_이메일_형식을_검증한다() throws Exception {
    String ticket = flow.sendAndVerifyPhone(PHONE);

    assertFieldError(
        mockMvc.perform(
            jsonPost(
                "/api/auth/signup/kakao",
                body("가".repeat(51), ticket, null, requiredTerms()),
                session)),
        "displayName",
        "TOO_LONG");
    assertFieldError(
        mockMvc.perform(
            jsonPost(
                "/api/auth/signup/kakao",
                body("홍길동", ticket, "not-mail", requiredTerms()),
                session)),
        "kakaoReferenceEmail",
        "INVALID_FORMAT");
  }

  @Test
  void 필수_약관이_빠지면_400이다() throws Exception {
    String ticket = flow.sendAndVerifyPhone(PHONE);

    assertFieldError(
        mockMvc.perform(
            jsonPost("/api/auth/signup/kakao", body("홍길동", ticket, null, "[]"), session)),
        "agreedTerms",
        "REQUIRED");
  }

  @Test
  void 로그인_없이_호출하면_401이다() throws Exception {
    assertError(
        mockMvc.perform(
            jsonPost("/api/auth/signup/kakao", body("홍길동", "x", null, requiredTerms()))),
        ErrorCode.UNAUTHENTICATED);
    assertThat(List.of(1)).isNotEmpty();
  }
}
