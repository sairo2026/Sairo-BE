package com.sairo.be.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "sairo.auth.auto-approve-staff=true")
class AuthAutoApproveStaffIntegrationTest {

  private static final String SESSION_COOKIE_NAME = "__Host-sairo_session";
  private static final String NEW_KAKAO_ID = "7001001";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @MockitoBean private KakaoOAuthClient kakaoOAuthClient;

  @BeforeEach
  void seed() {
    jdbcTemplate.update("DELETE FROM office_membership");
    jdbcTemplate.update("DELETE FROM app_user");
    jdbcTemplate.update("DELETE FROM office");
  }

  @Test
  void 자동승인_활성화시_최초_카카오_로그인은_임시사무소_직원으로_승인되어_홈으로_리다이렉트한다() throws Exception {
    ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
    when(kakaoOAuthClient.buildAuthorizeUrl(stateCaptor.capture()))
        .thenReturn("https://kauth.kakao.com/oauth/authorize");
    when(kakaoOAuthClient.exchangeToken(anyString())).thenReturn("access-token");
    when(kakaoOAuthClient.fetchUserId("access-token")).thenReturn(Long.valueOf(NEW_KAKAO_ID));

    MvcResult startResult = mockMvc.perform(get("/api/auth/kakao/start")).andReturn();
    Cookie preAuthCookie = extractSessionCookie(startResult.getResponse());
    String state = stateCaptor.getValue();

    mockMvc
        .perform(
            get("/api/auth/kakao/callback")
                .param("code", "valid-code")
                .param("state", state)
                .cookie(preAuthCookie))
        .andExpect(status().is3xxRedirection())
        .andExpect(header().string("Location", "http://localhost:3000/"));

    Long officeId =
        jdbcTemplate.queryForObject(
            "SELECT office_id FROM office_membership m JOIN app_user u ON u.id = m.user_id"
                + " WHERE u.kakao_provider_key = ?",
            Long.class,
            NEW_KAKAO_ID);
    assertThat(officeId).isNotNull();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT business_registration_number FROM office WHERE id = ?",
                String.class,
                officeId))
        .isEqualTo("0000000000");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM office_membership WHERE office_id = ? AND user_id ="
                    + " (SELECT id FROM app_user WHERE kakao_provider_key = ?)",
                String.class,
                officeId,
                NEW_KAKAO_ID))
        .isEqualTo("APPROVED");
  }

  @Test
  void 자동승인_활성화시_두_번째_로그인도_같은_임시사무소를_재사용한다() throws Exception {
    ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
    when(kakaoOAuthClient.buildAuthorizeUrl(stateCaptor.capture()))
        .thenReturn("https://kauth.kakao.com/oauth/authorize");
    when(kakaoOAuthClient.exchangeToken(anyString())).thenReturn("access-token");
    when(kakaoOAuthClient.fetchUserId("access-token")).thenReturn(Long.valueOf(NEW_KAKAO_ID));
    loginOnce(stateCaptor);

    when(kakaoOAuthClient.fetchUserId("access-token")).thenReturn(7002002L);
    loginOnce(stateCaptor);

    Long officeCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM office WHERE business_registration_number = '0000000000'",
            Long.class);
    assertThat(officeCount).isEqualTo(1L);
  }

  private void loginOnce(ArgumentCaptor<String> stateCaptor) throws Exception {
    MvcResult startResult = mockMvc.perform(get("/api/auth/kakao/start")).andReturn();
    Cookie preAuthCookie = extractSessionCookie(startResult.getResponse());
    String state = stateCaptor.getValue();
    mockMvc
        .perform(
            get("/api/auth/kakao/callback")
                .param("code", "valid-code")
                .param("state", state)
                .cookie(preAuthCookie))
        .andExpect(header().string("Location", "http://localhost:3000/"));
  }

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
