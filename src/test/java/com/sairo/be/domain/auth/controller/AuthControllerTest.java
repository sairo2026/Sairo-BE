package com.sairo.be.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.TestcontainersConfiguration;
import com.sairo.be.domain.auth.service.KakaoOAuthClient;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

  private static final String SESSION_COOKIE_NAME = "__Host-sairo_session";
  private static final String APPROVED_KAKAO_ID = "5001001";
  private static final String NO_MEMBERSHIP_KAKAO_ID = "5001002";
  private static final String UNREGISTERED_KAKAO_ID = "5009999";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @MockitoBean private KakaoOAuthClient kakaoOAuthClient;

  @BeforeEach
  void seed() {
    jdbcTemplate.update("DELETE FROM office_membership");
    jdbcTemplate.update("DELETE FROM app_user");
    jdbcTemplate.update("DELETE FROM office");
    jdbcTemplate.update(
        "INSERT INTO office (id, name, representative_name, business_registration_number,"
            + " real_estate_license_number, phone, address, created_at)"
            + " VALUES (9001, '사이로 데모 사무소', '김대표', '1234567890', '11223344556', '02-1234-5678',"
            + " '서울시 강남구', now())");
    jdbcTemplate.update(
        "INSERT INTO app_user (id, kakao_provider_key, name, created_at)"
            + " VALUES (9101, ?, '박직원', now())",
        APPROVED_KAKAO_ID);
    jdbcTemplate.update(
        "INSERT INTO app_user (id, kakao_provider_key, name, created_at)"
            + " VALUES (9102, ?, '미승인', now())",
        NO_MEMBERSHIP_KAKAO_ID);
    jdbcTemplate.update(
        "INSERT INTO office_membership (id, user_id, office_id, role, status, reviewed_at)"
            + " VALUES (9201, 9101, 9001, 'STAFF', 'APPROVED', now())");
  }

  @Test
  void start은_카카오_인가URL로_리다이렉트하고_세션에_state를_저장한다() throws Exception {
    when(kakaoOAuthClient.buildAuthorizeUrl(anyString()))
        .thenAnswer(
            invocation ->
                "https://kauth.kakao.com/oauth/authorize?state=" + invocation.getArgument(0));

    mockMvc
        .perform(get("/api/auth/kakao/start"))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            header()
                .string("Location", startsWith("https://kauth.kakao.com/oauth/authorize?state=")))
        .andExpect(header().exists("Set-Cookie"));
  }

  @Test
  void 유효한_state와_승인된_소속으로_콜백하면_홈으로_리다이렉트하고_인증세션을_발급한다() throws Exception {
    ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
    when(kakaoOAuthClient.buildAuthorizeUrl(stateCaptor.capture()))
        .thenReturn("https://kauth.kakao.com/oauth/authorize");
    when(kakaoOAuthClient.exchangeToken("valid-code")).thenReturn("access-token");
    when(kakaoOAuthClient.fetchUserId("access-token")).thenReturn(Long.valueOf(APPROVED_KAKAO_ID));

    MvcResult startResult = mockMvc.perform(get("/api/auth/kakao/start")).andReturn();
    Cookie preAuthCookie = extractSessionCookie(startResult.getResponse());
    assertThat(preAuthCookie).isNotNull();
    String state = stateCaptor.getValue();

    MvcResult callbackResult =
        mockMvc
            .perform(
                get("/api/auth/kakao/callback")
                    .param("code", "valid-code")
                    .param("state", state)
                    .cookie(preAuthCookie))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", "http://localhost:3000/"))
            .andReturn();

    Cookie authenticatedCookie = extractSessionCookie(callbackResult.getResponse());
    assertThat(authenticatedCookie).isNotNull();
    assertThat(authenticatedCookie.getValue()).isNotEqualTo(preAuthCookie.getValue());

    String setCookieHeader = callbackResult.getResponse().getHeader("Set-Cookie");
    assertThat(setCookieHeader).contains("HttpOnly");
    assertThat(setCookieHeader).contains("Secure");
    assertThat(setCookieHeader).containsIgnoringCase("SameSite=Lax");

    mockMvc
        .perform(get("/api/unmapped-protected-path").cookie(authenticatedCookie))
        .andExpect(status().isNotFound());
  }

  @Test
  void state가_일치하지_않으면_로그인_화면으로_리다이렉트하고_세션을_발급하지_않는다() throws Exception {
    when(kakaoOAuthClient.buildAuthorizeUrl(anyString()))
        .thenReturn("https://kauth.kakao.com/oauth/authorize");

    MvcResult startResult = mockMvc.perform(get("/api/auth/kakao/start")).andReturn();
    Cookie preAuthCookie = extractSessionCookie(startResult.getResponse());

    mockMvc
        .perform(
            get("/api/auth/kakao/callback")
                .param("code", "valid-code")
                .param("state", "wrong-state")
                .cookie(preAuthCookie))
        .andExpect(status().is3xxRedirection())
        .andExpect(header().string("Location", "http://localhost:3000/login?error=auth_failed"));
  }

  @Test
  void 카카오_회원번호가_사전_시드된_계정과_일치하지_않으면_로그인_화면으로_리다이렉트한다() throws Exception {
    ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
    when(kakaoOAuthClient.buildAuthorizeUrl(stateCaptor.capture()))
        .thenReturn("https://kauth.kakao.com/oauth/authorize");
    when(kakaoOAuthClient.exchangeToken("valid-code")).thenReturn("access-token");
    when(kakaoOAuthClient.fetchUserId("access-token"))
        .thenReturn(Long.valueOf(UNREGISTERED_KAKAO_ID));

    MvcResult startResult = mockMvc.perform(get("/api/auth/kakao/start")).andReturn();
    Cookie preAuthCookie = extractSessionCookie(startResult.getResponse());
    assertThat(preAuthCookie).isNotNull();
    String state = stateCaptor.getValue();

    mockMvc
        .perform(
            get("/api/auth/kakao/callback")
                .param("code", "valid-code")
                .param("state", state)
                .cookie(preAuthCookie))
        .andExpect(status().is3xxRedirection())
        .andExpect(header().string("Location", "http://localhost:3000/login?error=auth_failed"));
  }

  @Test
  void 승인된_사무소_소속이_없으면_로그인_화면으로_리다이렉트한다() throws Exception {
    ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
    when(kakaoOAuthClient.buildAuthorizeUrl(stateCaptor.capture()))
        .thenReturn("https://kauth.kakao.com/oauth/authorize");
    when(kakaoOAuthClient.exchangeToken("valid-code")).thenReturn("access-token");
    when(kakaoOAuthClient.fetchUserId("access-token"))
        .thenReturn(Long.valueOf(NO_MEMBERSHIP_KAKAO_ID));

    MvcResult startResult = mockMvc.perform(get("/api/auth/kakao/start")).andReturn();
    Cookie preAuthCookie = extractSessionCookie(startResult.getResponse());
    assertThat(preAuthCookie).isNotNull();
    String state = stateCaptor.getValue();

    mockMvc
        .perform(
            get("/api/auth/kakao/callback")
                .param("code", "valid-code")
                .param("state", state)
                .cookie(preAuthCookie))
        .andExpect(status().is3xxRedirection())
        .andExpect(header().string("Location", "http://localhost:3000/login?error=auth_failed"));
  }

  @Test
  void 인증되지_않은_요청은_401을_반환한다() throws Exception {
    mockMvc.perform(get("/api/unmapped-protected-path")).andExpect(status().isUnauthorized());
  }

  // Spring Session's cookie serializer writes Set-Cookie via response.addHeader(...) directly,
  // not response.addCookie(...), so MockHttpServletResponse#getCookie(name) never sees it.
  private Cookie extractSessionCookie(MockHttpServletResponse response) {
    String prefix = SESSION_COOKIE_NAME + "=";
    for (String header : response.getHeaders("Set-Cookie")) {
      if (header.startsWith(prefix)) {
        String value = header.substring(prefix.length());
        int semicolon = value.indexOf(';');
        return new Cookie(
            SESSION_COOKIE_NAME, semicolon >= 0 ? value.substring(0, semicolon) : value);
      }
    }
    return null;
  }
}
