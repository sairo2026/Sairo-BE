package com.sairo.be.domain.auth.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StepUpState;
import com.sairo.be.support.AuthFlowSupport.KakaoStart;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.AppUserBuilder;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.Test;

@IntegrationTest
class StepUpIntegrationTest extends AuthIntegrationTestBase {

  private static final String EMAIL = "agent@example.com";

  private String passwordBody(String password) {
    return "{\"password\":" + quote(password) + "}";
  }

  @Test
  void 비밀번호_재인증은_10분_상태를_기록하고_세션_조회에_반영된다() throws Exception {
    saveEmailUser(EMAIL, null);
    Cookie session = login(EMAIL, PASSWORD);

    mockMvc
        .perform(jsonPost("/api/auth/step-up/password", passwordBody(PASSWORD), session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stepUpValid").value(true))
        .andExpect(jsonPath("$.stepUpExpiresAt").isNotEmpty());

    Instant until = (Instant) sessionAttribute(session, StepUpState.STEP_UP_UNTIL_ATTRIBUTE);
    assertThat(until).isBetween(Instant.now().plusSeconds(590), Instant.now().plusSeconds(601));
    mockMvc
        .perform(get("/api/me/session").cookie(session))
        .andExpect(jsonPath("$.stepUpValid").value(true));
  }

  @Test
  void 틀린_비밀번호는_401이고_재인증_상태를_만들지_않는다() throws Exception {
    saveEmailUser(EMAIL, null);
    Cookie session = login(EMAIL, PASSWORD);

    assertError(
        mockMvc.perform(
            jsonPost("/api/auth/step-up/password", passwordBody("wrong-password"), session)),
        ErrorCode.INVALID_CREDENTIALS);
    assertThat(sessionAttribute(session, StepUpState.STEP_UP_UNTIL_ATTRIBUTE)).isNull();
  }

  @Test
  void 재인증_비밀번호에는_8자에서_12자_제한을_적용하지_않고_앞뒤_공백을_보존한다() throws Exception {
    saveEmailUser(EMAIL, null);
    Cookie session = login(EMAIL, PASSWORD);

    assertError(
        mockMvc.perform(
            jsonPost("/api/auth/step-up/password", passwordBody(" " + PASSWORD), session)),
        ErrorCode.INVALID_CREDENTIALS);
    assertError(
        mockMvc.perform(jsonPost("/api/auth/step-up/password", passwordBody("x"), session)),
        ErrorCode.INVALID_CREDENTIALS);
  }

  @Test
  void 연속_5회_틀리면_정답이어도_429_LOGIN_LOCKED다() throws Exception {
    saveEmailUser(EMAIL, null);
    Cookie session = login(EMAIL, PASSWORD);
    for (int i = 0; i < 5; i++) {
      mockMvc.perform(
          jsonPost("/api/auth/step-up/password", passwordBody("wrong-password"), session));
    }

    assertError(
        mockMvc.perform(jsonPost("/api/auth/step-up/password", passwordBody(PASSWORD), session)),
        ErrorCode.LOGIN_LOCKED);
  }

  @Test
  void 카카오_전용_계정은_비밀번호_재인증을_할_수_없다() throws Exception {
    AppUser user =
        appUserRepository.saveAndFlush(
            AppUserBuilder.anAppUser().kakaoProviderKey("7001001").build());

    assertError(
        mockMvc.perform(
            jsonPost("/api/auth/step-up/password", passwordBody(PASSWORD), kakaoSessionFor(user))),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 로그인_없이_재인증하면_401이다() throws Exception {
    assertError(
        mockMvc.perform(jsonPost("/api/auth/step-up/password", passwordBody(PASSWORD))),
        ErrorCode.UNAUTHENTICATED);
    assertError(mockMvc.perform(get("/api/auth/step-up/kakao/start")), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 카카오_재인증은_같은_카카오_계정이면_10분_상태를_기록한다() throws Exception {
    AppUser user =
        appUserRepository.saveAndFlush(
            AppUserBuilder.anAppUser().kakaoProviderKey("7001002").build());
    Cookie session = kakaoSessionFor(user);
    KakaoStart start = flow.startKakao("/api/auth/step-up/kakao/start", session);
    assertThat(start.location()).contains("flow=STEP_UP");

    mockMvc
        .perform(
            get("/api/auth/step-up/kakao/callback")
                .cookie(session)
                .param("code", "7001002")
                .param("state", start.state()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stepUpValid").value(true))
        .andExpect(jsonPath("$.stepUpExpiresAt").isNotEmpty());
    assertThat(sessionAttribute(session, StepUpState.STEP_UP_UNTIL_ATTRIBUTE)).isNotNull();
  }

  @Test
  void 다른_카카오_계정으로_콜백하면_403이다() throws Exception {
    AppUser user =
        appUserRepository.saveAndFlush(
            AppUserBuilder.anAppUser().kakaoProviderKey("7001003").build());
    Cookie session = kakaoSessionFor(user);
    KakaoStart start = flow.startKakao("/api/auth/step-up/kakao/start", session);

    assertError(
        mockMvc.perform(
            get("/api/auth/step-up/kakao/callback")
                .cookie(session)
                .param("code", "9999999")
                .param("state", start.state())),
        ErrorCode.STEP_UP_ACCOUNT_MISMATCH);
    assertThat(sessionAttribute(session, StepUpState.STEP_UP_UNTIL_ATTRIBUTE)).isNull();
  }

  @Test
  void state가_틀리거나_재사용하면_400이다() throws Exception {
    AppUser user =
        appUserRepository.saveAndFlush(
            AppUserBuilder.anAppUser().kakaoProviderKey("7001004").build());
    Cookie session = kakaoSessionFor(user);
    KakaoStart start = flow.startKakao("/api/auth/step-up/kakao/start", session);

    assertError(
        mockMvc.perform(
            get("/api/auth/step-up/kakao/callback")
                .cookie(session)
                .param("code", "7001004")
                .param("state", "wrong")),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(
            get("/api/auth/step-up/kakao/callback")
                .cookie(session)
                .param("code", "7001004")
                .param("state", start.state())),
        ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 이메일_전용_계정은_카카오_재인증을_시작할_수_없다() throws Exception {
    saveEmailUser(EMAIL, null);
    Cookie session = login(EMAIL, PASSWORD);

    assertError(
        mockMvc.perform(get("/api/auth/step-up/kakao/start").cookie(session)),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 만료된_재인증은_유효하지_않다() throws Exception {
    saveEmailUser(EMAIL, null);
    Cookie session = login(EMAIL, PASSWORD);
    setSessionAttribute(
        session, StepUpState.STEP_UP_UNTIL_ATTRIBUTE, Instant.now().minusSeconds(1));

    mockMvc
        .perform(get("/api/me/session").cookie(session))
        .andExpect(jsonPath("$.stepUpValid").value(false));
  }
}
