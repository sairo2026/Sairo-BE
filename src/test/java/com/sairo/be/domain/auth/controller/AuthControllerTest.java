package com.sairo.be.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.TestcontainersConfiguration;
import com.sairo.be.domain.auth.service.KakaoOAuthClient;
import com.sairo.be.domain.auth.service.OAuthStateService;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
  @MockitoSpyBean private OAuthStateService oauthStateService;
  @Autowired private JdbcIndexedSessionRepository sessionRepository;

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

    String sessionSetCookieHeader =
        callbackResult.getResponse().getHeaders("Set-Cookie").stream()
            .filter(header -> header.startsWith(SESSION_COOKIE_NAME + "="))
            .findFirst()
            .orElseThrow();
    assertThat(sessionSetCookieHeader).contains("HttpOnly");
    assertThat(sessionSetCookieHeader).contains("Secure");
    assertThat(sessionSetCookieHeader).containsIgnoringCase("SameSite=Lax");

    assertThat(callbackResult.getResponse().getHeaders("Set-Cookie"))
        .anyMatch(header -> header.startsWith("XSRF-TOKEN="));

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

  @Test
  void 실패_콜백도_state를_소비하여_정상_코드로_재사용할_수_없다() throws Exception {
    for (boolean hasError : new boolean[] {true, false}) {
      var start = startLogin();
      var callback =
          get("/api/auth/kakao/callback").cookie(start.cookie()).param("state", start.state());
      if (hasError) {
        callback.param("error", "access_denied").param("code", "valid-code");
      }
      mockMvc
          .perform(callback)
          .andExpect(header().string("Location", "http://localhost:3000/login?error=auth_failed"));
      mockMvc
          .perform(
              get("/api/auth/kakao/callback")
                  .cookie(start.cookie())
                  .param("state", start.state())
                  .param("code", "valid-code"))
          .andExpect(header().string("Location", "http://localhost:3000/login?error=auth_failed"));
    }
    verify(kakaoOAuthClient, never()).exchangeToken(anyString());
  }

  @Test
  void 만료된_JDBC_세션의_state는_콜백에서_사용할_수_없다() throws Exception {
    var start = startLogin();
    jdbcTemplate.update(
        "UPDATE SPRING_SESSION SET EXPIRY_TIME = ? WHERE SESSION_ID = ?",
        System.currentTimeMillis() - 1,
        sessionId(start.cookie()));
    mockMvc
        .perform(
            get("/api/auth/kakao/callback")
                .cookie(start.cookie())
                .param("state", start.state())
                .param("code", "valid-code"))
        .andExpect(header().string("Location", "http://localhost:3000/login?error=auth_failed"));
    verify(kakaoOAuthClient, never()).exchangeToken(anyString());
  }

  @Test
  void 동시에_같은_state로_콜백해도_한_요청만_인증하고_외부호출에는_트랜잭션이_없다() throws Exception {
    var start = startLogin();
    CountDownLatch snapshotsLoaded = new CountDownLatch(2);
    doAnswer(
            invocation -> {
              snapshotsLoaded.countDown();
              assertThat(snapshotsLoaded.await(10, TimeUnit.SECONDS)).isTrue();
              return invocation.callRealMethod();
            })
        .when(oauthStateService)
        .consume(anyString(), anyString(), anyString());
    when(kakaoOAuthClient.exchangeToken("valid-code"))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return "test-token";
            });
    when(kakaoOAuthClient.fetchUserId("test-token"))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return Long.valueOf(APPROVED_KAKAO_ID);
            });
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> callbackLocation(start));
      var second = executor.submit(() -> callbackLocation(start));
      assertThat(
              java.util.List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(
              "http://localhost:3000/", "http://localhost:3000/login?error=auth_failed");
    }
    verify(kakaoOAuthClient, times(1)).exchangeToken("valid-code");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM SPRING_SESSION_ATTRIBUTES WHERE ATTRIBUTE_NAME = ? AND ATTRIBUTE_BYTES = ?",
                Integer.class,
                OAuthStateService.STATE_SESSION_ATTRIBUTE,
                org.springframework.util.SerializationUtils.serialize(start.state())))
        .isZero();
  }

  @Test
  void 이전_세션_snapshot은_새_state를_소비하거나_소비된_state를_복원하지_않는다() throws Exception {
    var start = startLogin();
    var stale = sessionRepository.findById(sessionId(start.cookie()));
    assertThat(stale).isNotNull();
    String staleId = ((Session) stale).getId();
    assertThat(oauthStateService.consume(staleId, start.state(), start.state())).isTrue();
    ((Session) stale).setLastAccessedTime(java.time.Instant.now());
    sessionRepository.save(stale);
    assertThat(oauthStateService.consume(staleId, start.state(), start.state())).isFalse();

    var current = sessionRepository.findById(staleId);
    ((Session) current).setAttribute(OAuthStateService.STATE_SESSION_ATTRIBUTE, "new-state");
    sessionRepository.save(current);
    assertThat(oauthStateService.consume(staleId, start.state(), start.state())).isFalse();
    assertThat(oauthStateService.consume(((Session) current).getId(), "new-state", "new-state"))
        .isTrue();
  }

  private LoginStart startLogin() throws Exception {
    ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
    when(kakaoOAuthClient.buildAuthorizeUrl(stateCaptor.capture()))
        .thenReturn("https://kauth.kakao.com/oauth/authorize");
    var result = mockMvc.perform(get("/api/auth/kakao/start")).andReturn();
    return new LoginStart(extractSessionCookie(result.getResponse()), stateCaptor.getValue());
  }

  @Test
  void 이전_세션의_늦은_저장이_인증_세션_ID를_되돌리지_않는다() throws Exception {
    var start = startLogin();
    var stale = sessionRepository.findById(sessionId(start.cookie()));
    assertThat(stale).isNotNull();
    when(kakaoOAuthClient.exchangeToken("valid-code")).thenReturn("test-token");
    when(kakaoOAuthClient.fetchUserId("test-token")).thenReturn(Long.valueOf(APPROVED_KAKAO_ID));
    var result =
        mockMvc
            .perform(
                get("/api/auth/kakao/callback")
                    .cookie(start.cookie())
                    .param("state", start.state())
                    .param("code", "valid-code"))
            .andExpect(header().string("Location", "http://localhost:3000/"))
            .andReturn();
    var authenticatedCookie = extractSessionCookie(result.getResponse());
    assertThat(authenticatedCookie).isNotNull();
    ((Session) stale).setLastAccessedTime(java.time.Instant.now());
    sessionRepository.save(stale);

    mockMvc
        .perform(get("/api/unmapped-protected-path").cookie(authenticatedCookie))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/unmapped-protected-path").cookie(start.cookie()))
        .andExpect(status().isUnauthorized());
    assertThat(sessionRepository.findById(sessionId(start.cookie()))).isNull();
  }

  @Test
  void 탈퇴한_사용자는_승인된_소속이_남아_있어도_로그인할_수_없다() throws Exception {
    jdbcTemplate.update(
        "UPDATE app_user SET account_status = 'WITHDRAWN', withdrawn_at = now() WHERE id = 9101");
    var start = startLogin();
    when(kakaoOAuthClient.exchangeToken("valid-code")).thenReturn("test-token");
    when(kakaoOAuthClient.fetchUserId("test-token")).thenReturn(Long.valueOf(APPROVED_KAKAO_ID));
    assertThat(callbackLocation(start)).isEqualTo("http://localhost:3000/login?error=auth_failed");
  }

  private String callbackLocation(LoginStart start) throws Exception {
    return mockMvc
        .perform(
            get("/api/auth/kakao/callback")
                .cookie(start.cookie())
                .param("state", start.state())
                .param("code", "valid-code"))
        .andReturn()
        .getResponse()
        .getHeader("Location");
  }

  private String sessionId(Cookie cookie) {
    return new String(Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8);
  }

  private record LoginStart(Cookie cookie, String state) {}

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
