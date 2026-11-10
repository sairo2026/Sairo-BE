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
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@IntegrationTest
class AccountSettingsIntegrationTest extends AuthIntegrationTestBase {

  private Cookie session;
  private Long userId;

  @BeforeEach
  void createUser() throws Exception {
    userId = saveEmailUser("agent@example.com", "010-1234-5678").getId();
    session = login("agent@example.com", PASSWORD);
  }

  private void agreeMarketing() throws Exception {
    mockMvc
        .perform(
            jsonPost(
                "/api/me/terms-agreements",
                "{\"termsCode\":\"MARKETING\",\"version\":\"2026-09-01\",\"agreed\":true,"
                    + "\"consentPath\":\"ACCOUNT_SETTINGS\"}",
                session))
        .andExpect(status().isNoContent());
  }

  @Test
  void 표시_이름을_수정하면_응답과_조회에_반영된다() throws Exception {
    mockMvc
        .perform(jsonPatch("/api/me", "{\"displayName\":\"  새이름  \"}", session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(userId))
        .andExpect(jsonPath("$.displayName").value("새이름"))
        .andExpect(jsonPath("$.email").value("agent@example.com"))
        .andExpect(jsonPath("$.emailVerified").value(true))
        .andExpect(jsonPath("$.phone").value("010-1234-5678"))
        .andExpect(jsonPath("$.phoneVerified").value(true))
        .andExpect(jsonPath("$.kakaoLinked").value(false))
        .andExpect(jsonPath("$.accountStatus").value("ACTIVE"))
        .andExpect(jsonPath("$.createdAt").isNotEmpty());
    mockMvc
        .perform(get("/api/me").cookie(session))
        .andExpect(jsonPath("$.displayName").value("새이름"));
  }

  @Test
  void 표시_이름은_1자에서_50자이고_HTML을_허용하지_않는다() throws Exception {
    assertFieldError(
        mockMvc.perform(
            jsonPatch("/api/me", "{\"displayName\":\"" + "가".repeat(51) + "\"}", session)),
        "displayName",
        "TOO_LONG");
    assertFieldError(
        mockMvc.perform(jsonPatch("/api/me", "{\"displayName\":\"<b>x</b>\"}", session)),
        "displayName",
        "HTML_NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(jsonPatch("/api/me", "{\"displayName\":\"   \"}", session)),
        "displayName",
        "REQUIRED");
    mockMvc
        .perform(jsonPatch("/api/me", "{\"displayName\":\"" + "가".repeat(50) + "\"}", session))
        .andExpect(status().isOk());
  }

  @Test
  void 로그인_없이는_401이다() throws Exception {
    assertError(
        mockMvc.perform(jsonPatch("/api/me", "{\"displayName\":\"x\"}")),
        ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(jsonPatch("/api/me/notification-preferences", "{\"channels\":{}}")),
        ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 방문_알림을_끄면_저장하고_보안_알림은_항상_켜_둔다() throws Exception {
    mockMvc
        .perform(
            jsonPatch(
                "/api/me/notification-preferences",
                "{\"channels\":{\"VISIT_REMINDER\":false,\"SECURITY\":false}}",
                session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.channels.VISIT_REMINDER").value(false))
        .andExpect(jsonPath("$.channels.SECURITY").value(true))
        .andExpect(jsonPath("$.channels.MARKETING").value(false));

    mockMvc
        .perform(get("/api/me").cookie(session))
        .andExpect(jsonPath("$.notificationPreferences.channels.VISIT_REMINDER").value(false))
        .andExpect(jsonPath("$.notificationPreferences.channels.SECURITY").value(true));
    Integer securityRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app_user_notification_preference WHERE channel_key = 'SECURITY'",
            Integer.class);
    assertThat(securityRows).isZero();
  }

  @Test
  void 방문_알림은_다시_켤_수_있다() throws Exception {
    mockMvc.perform(
        jsonPatch(
            "/api/me/notification-preferences",
            "{\"channels\":{\"VISIT_REMINDER\":false}}",
            session));

    mockMvc
        .perform(
            jsonPatch(
                "/api/me/notification-preferences",
                "{\"channels\":{\"VISIT_REMINDER\":true}}",
                session))
        .andExpect(jsonPath("$.channels.VISIT_REMINDER").value(true));
  }

  @Test
  void 마케팅을_켜는_요청은_400이다() throws Exception {
    assertFieldError(
        mockMvc.perform(
            jsonPatch(
                "/api/me/notification-preferences",
                "{\"channels\":{\"MARKETING\":true}}",
                session)),
        "channels.MARKETING",
        "NOT_ALLOWED");
  }

  @Test
  void 마케팅을_끄면_철회_이벤트와_결과_통지를_만든다() throws Exception {
    agreeMarketing();

    mockMvc
        .perform(
            jsonPatch(
                "/api/me/notification-preferences",
                "{\"channels\":{\"MARKETING\":false}}",
                session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.channels.MARKETING").value(false));

    List<Map<String, Object>> events =
        jdbcTemplate.queryForList(
            "SELECT agreed, consent_path FROM terms_agreement WHERE terms_code = 'MARKETING'"
                + " ORDER BY id");
    assertThat(events).hasSize(2);
    assertThat(events.get(1))
        .containsEntry("agreed", false)
        .containsEntry("consent_path", "ACCOUNT_SETTINGS");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM consent_notice_delivery", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void 이미_끈_마케팅을_다시_꺼도_이벤트를_만들지_않는다() throws Exception {
    mockMvc
        .perform(
            jsonPatch(
                "/api/me/notification-preferences",
                "{\"channels\":{\"MARKETING\":false}}",
                session))
        .andExpect(status().isOk());

    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM terms_agreement", Integer.class))
        .isZero();
  }

  @Test
  void 알_수_없는_키와_빈_맵은_400이다() throws Exception {
    assertFieldError(
        mockMvc.perform(
            jsonPatch(
                "/api/me/notification-preferences", "{\"channels\":{\"OTHER\":true}}", session)),
        "channels.OTHER",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(
            jsonPatch("/api/me/notification-preferences", "{\"channels\":{}}", session)),
        "channels",
        "REQUIRED");
  }

  @Test
  void 서비스_필수_알림은_설정_키가_없다() throws Exception {
    mockMvc
        .perform(get("/api/me").cookie(session))
        .andExpect(jsonPath("$.notificationPreferences.channels.length()").value(3))
        .andExpect(jsonPath("$.notificationPreferences.mandatoryTypes.length()").value(1));
  }
}
