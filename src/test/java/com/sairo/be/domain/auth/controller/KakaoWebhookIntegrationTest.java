package com.sairo.be.domain.auth.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.AppUserBuilder;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@IntegrationTest
class KakaoWebhookIntegrationTest extends AuthIntegrationTestBase {

  private static final String URL = "/api/webhooks/kakao/unlink";
  private static final String APP_ID = "1234567";
  private static final String AUTH = "KakaoAK test-admin-key";

  private MockHttpServletRequestBuilder form(String appId, String userId, String referrer) {
    return formWithAuth(appId, userId, referrer, AUTH);
  }

  private MockHttpServletRequestBuilder formWithAuth(
      String appId, String userId, String referrer, String authorization) {
    return post(URL)
        .header("Authorization", authorization)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .param("app_id", appId)
        .param("user_id", userId)
        .param("referrer_type", referrer);
  }

  private List<Map<String, Object>> events() {
    return jdbcTemplate.queryForList(
        "SELECT direction, status, referrer_type, user_id, kakao_key_hmac, target_key_encrypted,"
            + " completed_at FROM legal_kakao_unlink_event ORDER BY id");
  }

  private void kakaoOnlyUser(String key) {
    appUserRepository.saveAndFlush(AppUserBuilder.anAppUser().kakaoProviderKey(key).build());
    jdbcTemplate.update(
        "UPDATE app_user SET phone = '010-1111-2222', phone_verified_at = now(),"
            + " kakao_reference_email = 'ref@example.com'");
  }

  @Test
  void 인증값이_없거나_틀리면_401_WEBHOOK_AUTH_FAILED다() throws Exception {
    assertError(
        mockMvc.perform(
            post(URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("app_id", APP_ID)
                .param("user_id", "1")
                .param("referrer_type", "UNLINK_FROM_APPS")),
        ErrorCode.WEBHOOK_AUTH_FAILED);
    assertError(
        mockMvc.perform(formWithAuth(APP_ID, "1", "UNLINK_FROM_APPS", "KakaoAK wrong")),
        ErrorCode.WEBHOOK_AUTH_FAILED);
    assertError(
        mockMvc.perform(formWithAuth(APP_ID, "1", "UNLINK_FROM_APPS", "Bearer test-admin-key")),
        ErrorCode.WEBHOOK_AUTH_FAILED);
    assertThat(events()).isEmpty();
  }

  @Test
  void 세션_쿠키만으로는_수신_엔드포인트를_호출할_수_없다() throws Exception {
    saveEmailUser("agent@example.com", null);
    Cookie session = login("agent@example.com", PASSWORD);

    assertError(
        mockMvc.perform(
            post(URL)
                .cookie(session)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("app_id", APP_ID)
                .param("user_id", "1")
                .param("referrer_type", "UNLINK_FROM_APPS")),
        ErrorCode.WEBHOOK_AUTH_FAILED);
  }

  @Test
  void 이메일_로그인이_있는_계정은_카카오_식별값을_즉시_비우고_세션을_유지한다() throws Exception {
    saveEmailUser("agent@example.com", "010-1111-2222");
    jdbcTemplate.update(
        "UPDATE app_user SET kakao_provider_key = '9001001', kakao_reference_email = 'ref@example.com'");
    Cookie session = login("agent@example.com", PASSWORD);

    mockMvc
        .perform(form(APP_ID, "9001001", "UNLINK_FROM_APPS"))
        .andExpect(status().isOk())
        .andExpect(content().string(""));

    Map<String, Object> user =
        jdbcTemplate.queryForMap(
            "SELECT kakao_provider_key, kakao_unlinked_at, kakao_reference_email FROM app_user");
    assertThat(user.get("kakao_provider_key")).isNull();
    assertThat(user.get("kakao_unlinked_at")).isNotNull();
    assertThat(user.get("kakao_reference_email")).isNull();
    mockMvc.perform(get("/api/auth/context").cookie(session)).andExpect(status().isOk());
    assertThat(events()).hasSize(1);
    assertThat(events().get(0))
        .containsEntry("direction", "INBOUND_WEBHOOK")
        .containsEntry("status", "APPLIED")
        .containsEntry("referrer_type", "UNLINK_FROM_APPS");
    assertThat(events().get(0).get("target_key_encrypted")).isNull();
    assertThat(events().get(0).get("completed_at")).isNotNull();
    assertThat(mailSender.sent()).hasSize(1);
    assertThat(mailSender.sent().get(0).to()).isEqualTo("agent@example.com");
  }

  @Test
  void 카카오가_유일한_수단이면_모든_세션을_종료하고_식별값을_30일_보관_대상으로_남긴다() throws Exception {
    kakaoOnlyUser("9001002");
    long userId = jdbcTemplate.queryForObject("SELECT id FROM app_user", Long.class);
    Cookie session = kakaoSessionFor(appUserRepository.findById(userId).orElseThrow());
    mockMvc.perform(get("/api/auth/context").cookie(session)).andExpect(status().isOk());

    mockMvc.perform(form(APP_ID, "9001002", "ACCOUNT_DELETE")).andExpect(status().isOk());

    Map<String, Object> user =
        jdbcTemplate.queryForMap(
            "SELECT kakao_provider_key, kakao_unlinked_at, kakao_reference_email FROM app_user");
    assertThat(user.get("kakao_provider_key")).isEqualTo("9001002");
    assertThat(user.get("kakao_unlinked_at")).isNotNull();
    assertThat(user.get("kakao_reference_email")).isNull();
    mockMvc.perform(get("/api/auth/context").cookie(session)).andExpect(status().isUnauthorized());
    assertThat(mailSender.sent()).isEmpty();
  }

  @Test
  void 사용자가_없어도_같은_200_빈_응답이고_NO_MATCH로_닫는다() throws Exception {
    mockMvc
        .perform(form(APP_ID, "9999999", "UNLINK_FROM_ADMIN"))
        .andExpect(status().isOk())
        .andExpect(content().string(""));

    assertThat(events()).hasSize(1);
    assertThat(events().get(0)).containsEntry("status", "NO_MATCH").containsEntry("user_id", null);
    assertThat(events().get(0).get("kakao_key_hmac").toString())
        .hasSize(64)
        .doesNotContain("9999999");
  }

  @Test
  void 앱_ID가_다르면_반영하지_않고_NO_MATCH다() throws Exception {
    kakaoOnlyUser("9001003");

    mockMvc.perform(form("other-app", "9001003", "UNLINK_FROM_APPS")).andExpect(status().isOk());

    assertThat(events().get(0)).containsEntry("status", "NO_MATCH");
    assertThat(jdbcTemplate.queryForObject("SELECT kakao_unlinked_at FROM app_user", Object.class))
        .isNull();
  }

  @Test
  void 같은_식별값과_경로가_10분_안에_다시_오면_중복으로_기록하고_반영하지_않는다() throws Exception {
    kakaoOnlyUser("9001004");
    mockMvc.perform(form(APP_ID, "9001004", "UNLINK_FROM_APPS")).andExpect(status().isOk());
    jdbcTemplate.update("UPDATE app_user SET kakao_unlinked_at = NULL");

    mockMvc.perform(form(APP_ID, "9001004", "UNLINK_FROM_APPS")).andExpect(status().isOk());

    assertThat(events())
        .extracting(e -> e.get("status"))
        .containsExactly("APPLIED", "DUPLICATE_IGNORED");
    assertThat(jdbcTemplate.queryForObject("SELECT kakao_unlinked_at FROM app_user", Object.class))
        .isNull();
  }

  @Test
  void 경로가_다르면_중복이_아니다() throws Exception {
    kakaoOnlyUser("9001005");
    mockMvc.perform(form(APP_ID, "9001005", "UNLINK_FROM_APPS"));

    mockMvc.perform(form(APP_ID, "9001005", "ACCOUNT_DELETE")).andExpect(status().isOk());

    assertThat(events()).extracting(e -> e.get("status")).containsExactly("APPLIED", "APPLIED");
  }

  @Test
  void 카카오가_새_경로_값을_보내도_거절하지_않고_그대로_저장한다() throws Exception {
    mockMvc.perform(form(APP_ID, "9001006", "BRAND_NEW_REASON")).andExpect(status().isOk());

    assertThat(events().get(0)).containsEntry("referrer_type", "BRAND_NEW_REASON");
  }

  @Test
  void 쿼리_문자열과_JSON_본문도_읽는다() throws Exception {
    kakaoOnlyUser("9001007");

    mockMvc
        .perform(
            post(URL + "?app_id=" + APP_ID + "&user_id=9001007&referrer_type=UNLINK_FROM_APPS")
                .header("Authorization", AUTH))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post(URL)
                .header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"appId\":\"1234567\",\"userId\":\"9001008\",\"referrerType\":\"ACCOUNT_DELETE\","
                        + "\"groupUserToken\":\"ignored\"}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post(URL)
                .header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"app_id\":\"1234567\",\"user_id\":\"9001009\",\"referrer_type\":\"UNLINK_FROM_ADMIN\"}"))
        .andExpect(status().isOk());

    assertThat(events()).hasSize(3);
    assertThat(events().get(0)).containsEntry("status", "APPLIED");
  }

  @Test
  void 필수_파라미터가_빠지면_400이다() throws Exception {
    mockMvc
        .perform(
            post(URL)
                .header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("app_id", APP_ID))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post(URL)
                .header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("not json"))
        .andExpect(status().isBadRequest());
    assertThat(events()).isEmpty();
  }

  @Test
  void 같은_요청을_여러_번_받아도_결과가_같다() throws Exception {
    kakaoOnlyUser("9001010");

    for (int i = 0; i < 3; i++) {
      mockMvc.perform(form(APP_ID, "9001010", "UNLINK_FROM_APPS")).andExpect(status().isOk());
    }

    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user", Integer.class))
        .isEqualTo(1);
    assertThat(events())
        .extracting(e -> e.get("status"))
        .containsExactly("APPLIED", "DUPLICATE_IGNORED", "DUPLICATE_IGNORED");
  }

  @Test
  void 카카오로_다시_인증하면_끊김_상태를_풀고_같은_계정에_연결한다() throws Exception {
    kakaoOnlyUser("9001011");
    mockMvc.perform(form(APP_ID, "9001011", "UNLINK_FROM_APPS")).andExpect(status().isOk());
    var start = flow.startKakao("/api/auth/kakao/start", null);

    mockMvc
        .perform(
            get("/api/auth/kakao/callback")
                .cookie(start.cookie())
                .param("code", "9001011")
                .param("state", start.state()))
        .andExpect(status().is3xxRedirection());

    assertThat(jdbcTemplate.queryForObject("SELECT kakao_unlinked_at FROM app_user", Object.class))
        .isNull();
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user", Integer.class))
        .isEqualTo(1);
  }
}
