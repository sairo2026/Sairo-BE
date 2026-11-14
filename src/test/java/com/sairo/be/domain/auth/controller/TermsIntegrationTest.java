package com.sairo.be.domain.auth.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.auth.service.MarketingUnsubscribeTokens;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.TermsFixtures;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class TermsIntegrationTest extends AuthIntegrationTestBase {

  private static final String EMAIL = "agent@example.com";

  @Autowired private MarketingUnsubscribeTokens unsubscribeTokens;

  private Cookie session;
  private Long userId;

  @BeforeEach
  void createUser() throws Exception {
    userId = saveEmailUser(EMAIL, null).getId();
    session = login(EMAIL, PASSWORD);
  }

  private String agreementBody(String code, String version, boolean agreed, String path) {
    return "{\"termsCode\":\""
        + code
        + "\",\"version\":\""
        + version
        + "\",\"agreed\":"
        + agreed
        + ",\"consentPath\":\""
        + path
        + "\"}";
  }

  private List<Map<String, Object>> agreements() {
    return jdbcTemplate.queryForList(
        "SELECT terms_code, terms_version, agreed, consent_path FROM terms_agreement ORDER BY id");
  }

  @Test
  void 약관_목록은_시행_중인_버전을_문서_형식으로_돌려준다() throws Exception {
    mockMvc
        .perform(get("/api/terms"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(3))
        .andExpect(jsonPath("$.items[?(@.termsCode=='TOS')].required").value(true))
        .andExpect(jsonPath("$.items[?(@.termsCode=='TOS')].category").value("TERMS"))
        .andExpect(
            jsonPath("$.items[?(@.termsCode=='TOS')].documentUrl")
                .value("/legal/terms/TOS/2026-09-01"))
        .andExpect(jsonPath("$.items[?(@.termsCode=='PRIVACY')].category").value("NOTICE"))
        .andExpect(jsonPath("$.items[?(@.termsCode=='PRIVACY')].required").value(false))
        .andExpect(jsonPath("$.items[?(@.termsCode=='MARKETING')].required").value(false))
        .andExpect(jsonPath("$.items[?(@.termsCode=='MARKETING')].reconsentRequired").value(false));
  }

  @Test
  void 약관_목록은_로그인_없이_조회할_수_있다() throws Exception {
    mockMvc.perform(get("/api/terms")).andExpect(status().isOk());
  }

  @Test
  void 시행_전_버전은_목록에_없고_시행_중인_가장_최근_버전만_나온다() throws Exception {
    TermsFixtures.insert(
        jdbcTemplate,
        "TOS",
        "2026-12-01",
        "서비스 이용약관",
        "TERMS",
        true,
        "2026-06-01T00:00:00Z",
        false,
        "개정 1");
    TermsFixtures.insert(
        jdbcTemplate,
        "TOS",
        "2099-01-01",
        "서비스 이용약관",
        "TERMS",
        true,
        "2099-01-01T00:00:00Z",
        false,
        "미래");

    mockMvc
        .perform(get("/api/terms"))
        .andExpect(jsonPath("$.items.length()").value(3))
        .andExpect(jsonPath("$.items[?(@.termsCode=='TOS')].version").value("2026-12-01"))
        .andExpect(jsonPath("$.items[?(@.termsCode=='TOS')].changeSummary").value("개정 1"));
  }

  @Test
  void 알_수_없는_context는_400이다() throws Exception {
    mockMvc
        .perform(get("/api/terms").param("context", "UNKNOWN"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void 선택_약관을_철회하면_이벤트와_수신_설정과_결과_통지를_같은_트랜잭션에서_기록한다() throws Exception {
    mockMvc
        .perform(
            jsonPost(
                "/api/me/terms-agreements",
                agreementBody("MARKETING", "2026-09-01", false, "ACCOUNT_SETTINGS"),
                session))
        .andExpect(status().isNoContent());

    assertThat(agreements()).hasSize(1);
    assertThat(agreements().get(0))
        .containsEntry("terms_code", "MARKETING")
        .containsEntry("agreed", false)
        .containsEntry("consent_path", "ACCOUNT_SETTINGS");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT enabled FROM app_user_notification_preference"
                    + " WHERE channel_key = 'MARKETING'",
                Boolean.class))
        .isFalse();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT notice_type FROM consent_notice_delivery", String.class))
        .isEqualTo("MARKETING_RESULT");
  }

  @Test
  void 선택_약관에_동의하면_수신_설정을_켠다() throws Exception {
    mockMvc
        .perform(
            jsonPost(
                "/api/me/terms-agreements",
                agreementBody("MARKETING", "2026-09-01", true, "ACCOUNT_SETTINGS"),
                session))
        .andExpect(status().isNoContent());

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT enabled FROM app_user_notification_preference"
                    + " WHERE channel_key = 'MARKETING'",
                Boolean.class))
        .isTrue();
    mockMvc
        .perform(get("/api/me").cookie(session))
        .andExpect(jsonPath("$.notificationPreferences.channels.MARKETING").value(true))
        .andExpect(jsonPath("$.termsAgreements[0].termsCode").value("MARKETING"))
        .andExpect(jsonPath("$.termsAgreements[0].agreed").value(true))
        .andExpect(jsonPath("$.termsAgreements[0].withdrawable").value(true))
        .andExpect(jsonPath("$.termsAgreements[0].termsName").value("마케팅 정보 수신 동의"));
  }

  @Test
  void 같은_상태를_다시_보내면_이벤트를_중복으로_만들지_않는다() throws Exception {
    String body = agreementBody("MARKETING", "2026-09-01", true, "ACCOUNT_SETTINGS");
    mockMvc.perform(jsonPost("/api/me/terms-agreements", body, session));
    mockMvc
        .perform(jsonPost("/api/me/terms-agreements", body, session))
        .andExpect(status().isNoContent());

    assertThat(agreements()).hasSize(1);
  }

  @Test
  void 필수_약관_철회는_409다() throws Exception {
    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/me/terms-agreements",
                agreementBody("TOS", "2026-09-01", false, "ACCOUNT_SETTINGS"),
                session)),
        ErrorCode.TERMS_WITHDRAWAL_NOT_ALLOWED);
    assertThat(agreements()).isEmpty();
  }

  @Test
  void 열람_고지_항목은_철회도_동의도_할_수_없다() throws Exception {
    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/me/terms-agreements",
                agreementBody("PRIVACY", "2026-09-01", false, "ACCOUNT_SETTINGS"),
                session)),
        ErrorCode.TERMS_WITHDRAWAL_NOT_ALLOWED);
    assertFieldError(
        mockMvc.perform(
            jsonPost(
                "/api/me/terms-agreements",
                agreementBody("PRIVACY", "2026-09-01", true, "ACCOUNT_SETTINGS"),
                session)),
        "termsCode",
        "NOT_ALLOWED");
  }

  @Test
  void 시행_버전이_아닌_버전에_동의하면_409와_바뀐_항목을_돌려준다() throws Exception {
    assertError(
            mockMvc.perform(
                jsonPost(
                    "/api/me/terms-agreements",
                    agreementBody("MARKETING", "2025-01-01", true, "ACCOUNT_SETTINGS"),
                    session)),
            ErrorCode.TERMS_VERSION_CHANGED)
        .andExpect(jsonPath("$.changedItems[0].submittedVersion").value("2025-01-01"))
        .andExpect(jsonPath("$.changedItems[0].currentVersion").value("2026-09-01"));
  }

  @Test
  void 철회는_제출_버전과_무관하게_시행_버전으로_기록한다() throws Exception {
    mockMvc
        .perform(
            jsonPost(
                "/api/me/terms-agreements",
                agreementBody("MARKETING", "2020-01-01", false, "ACCOUNT_SETTINGS"),
                session))
        .andExpect(status().isNoContent());

    assertThat(agreements().get(0)).containsEntry("terms_version", "2026-09-01");
  }

  @Test
  void 클라이언트가_보낼_수_없는_동의_경로는_400이다() throws Exception {
    assertFieldError(
        mockMvc.perform(
            jsonPost(
                "/api/me/terms-agreements",
                agreementBody("MARKETING", "2026-09-01", true, "SIGNUP_EMAIL"),
                session)),
        "consentPath",
        "NOT_ALLOWED");
  }

  @Test
  void 알_수_없는_약관_코드는_400이다() throws Exception {
    assertFieldError(
        mockMvc.perform(
            jsonPost(
                "/api/me/terms-agreements",
                agreementBody("NOPE", "2026-09-01", true, "ACCOUNT_SETTINGS"),
                session)),
        "termsCode",
        "INVALID_VALUE");
  }

  @Test
  void 인증된_이메일이_없는_계정의_마케팅_동의는_422다() throws Exception {
    jdbcTemplate.update("UPDATE app_user SET email_verified_at = NULL");

    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/me/terms-agreements",
                agreementBody("MARKETING", "2026-09-01", true, "ACCOUNT_SETTINGS"),
                session)),
        ErrorCode.MARKETING_EMAIL_REQUIRED);
  }

  @Test
  void 로그인_없이_동의_기록을_요청하면_401이다() throws Exception {
    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/me/terms-agreements",
                agreementBody("MARKETING", "2026-09-01", true, "ACCOUNT_SETTINGS"))),
        ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 재동의가_필요한_개정이_시행되면_업무_요청은_403이고_면제_엔드포인트는_허용한다() throws Exception {
    TermsFixtures.insert(
        jdbcTemplate,
        "TOS",
        "2026-12-01",
        "서비스 이용약관",
        "TERMS",
        true,
        "2026-06-01T00:00:00Z",
        true,
        "자동결제 안내 문구가 추가되었습니다");
    jdbcTemplate.update(
        "INSERT INTO terms_agreement (user_id, terms_code, terms_version, agreed, consent_path)"
            + " VALUES (?, 'TOS', '2026-09-01', true, 'SIGNUP_EMAIL')",
        userId);

    assertError(
        mockMvc.perform(get("/api/me/login-history").cookie(session)),
        ErrorCode.RECONSENT_REQUIRED);
    assertError(mockMvc.perform(get("/api/me").cookie(session)), ErrorCode.RECONSENT_REQUIRED);
    mockMvc
        .perform(get("/api/auth/context").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reconsentRequired").value(true));
    mockMvc.perform(get("/api/me/session").cookie(session)).andExpect(status().isOk());
    mockMvc
        .perform(get("/api/me/terms-reconsent").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reconsentRequired").value(true))
        .andExpect(jsonPath("$.items[0].termsCode").value("TOS"))
        .andExpect(jsonPath("$.items[0].previousVersion").value("2026-09-01"))
        .andExpect(jsonPath("$.items[0].version").value("2026-12-01"))
        .andExpect(jsonPath("$.items[0].category").value("TERMS"))
        .andExpect(jsonPath("$.items[0].required").value(true))
        .andExpect(jsonPath("$.items[0].changeSummary").value("자동결제 안내 문구가 추가되었습니다"))
        .andExpect(jsonPath("$.items[0].documentUrl").value("/legal/terms/TOS/2026-12-01"))
        .andExpect(jsonPath("$.items[0].previousDocumentUrl").value("/legal/terms/TOS/2026-09-01"));

    mockMvc
        .perform(
            jsonPost(
                "/api/me/terms-agreements",
                agreementBody("TOS", "2026-12-01", true, "RECONSENT"),
                session))
        .andExpect(status().isNoContent());

    mockMvc.perform(get("/api/me/login-history").cookie(session)).andExpect(status().isOk());
    mockMvc
        .perform(get("/api/me/terms-reconsent").cookie(session))
        .andExpect(jsonPath("$.reconsentRequired").value(false))
        .andExpect(jsonPath("$.items.length()").value(0));
    assertThat(agreements().get(agreements().size() - 1))
        .containsEntry("consent_path", "RECONSENT")
        .containsEntry("terms_version", "2026-12-01");
  }

  @Test
  void 재동의_제한은_로그아웃과_공개_엔드포인트에는_적용하지_않는다() throws Exception {
    TermsFixtures.insert(
        jdbcTemplate,
        "TOS",
        "2026-12-01",
        "서비스 이용약관",
        "TERMS",
        true,
        "2026-06-01T00:00:00Z",
        true,
        "개정");

    mockMvc.perform(get("/api/terms")).andExpect(status().isOk());
    mockMvc.perform(jsonPost("/api/auth/logout", "{}", session)).andExpect(status().isNoContent());
  }

  @Test
  void 고지만_하는_개정은_재동의를_요구하지_않는다() throws Exception {
    TermsFixtures.insert(
        jdbcTemplate,
        "TOS",
        "2026-12-01",
        "서비스 이용약관",
        "TERMS",
        true,
        "2026-06-01T00:00:00Z",
        false,
        "고지");

    mockMvc.perform(get("/api/me/login-history").cookie(session)).andExpect(status().isOk());
    mockMvc
        .perform(get("/api/me/terms-reconsent").cookie(session))
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  @Test
  void 시행_전인_재동의_개정은_제한하지_않는다() throws Exception {
    TermsFixtures.insert(
        jdbcTemplate,
        "TOS",
        "2099-01-01",
        "서비스 이용약관",
        "TERMS",
        true,
        "2099-01-01T00:00:00Z",
        true,
        "미래");

    mockMvc.perform(get("/api/me/login-history").cookie(session)).andExpect(status().isOk());
  }

  @Test
  void 처음_동의하는_재동의_항목은_이전_버전이_없다() throws Exception {
    TermsFixtures.insert(
        jdbcTemplate,
        "SEPARATE",
        "2026-12-01",
        "별도 동의",
        "SEPARATE_CONSENT",
        true,
        "2026-06-01T00:00:00Z",
        true,
        "신규 항목");

    mockMvc
        .perform(get("/api/me/terms-reconsent").cookie(session))
        .andExpect(jsonPath("$.items[0].termsCode").value("SEPARATE"))
        .andExpect(jsonPath("$.items[0].previousVersion").isEmpty())
        .andExpect(jsonPath("$.items[0].previousDocumentUrl").isEmpty());
  }

  @Test
  void 수신_거부_링크는_철회를_기록하고_이미_철회한_뒤에는_같은_결과를_돌려준다() throws Exception {
    mockMvc.perform(
        jsonPost(
            "/api/me/terms-agreements",
            agreementBody("MARKETING", "2026-09-01", true, "ACCOUNT_SETTINGS"),
            session));
    String token = unsubscribeTokens.issue(userId);

    mockMvc
        .perform(jsonPost("/api/public/marketing-unsubscribe", "{\"token\":\"" + token + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UNSUBSCRIBED"))
        .andExpect(jsonPath("$.emailNoticeScheduled").value(true))
        .andExpect(jsonPath("$.expressedAt").isNotEmpty())
        .andExpect(jsonPath("$.resultNoticeDueAt").isNotEmpty());
    mockMvc
        .perform(jsonPost("/api/public/marketing-unsubscribe", "{\"token\":\"" + token + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ALREADY_UNSUBSCRIBED"))
        .andExpect(jsonPath("$.emailNoticeScheduled").value(false));

    assertThat(agreements()).hasSize(2);
    assertThat(agreements().get(1))
        .containsEntry("agreed", false)
        .containsEntry("consent_path", "UNSUBSCRIBE_LINK");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT enabled FROM app_user_notification_preference"
                    + " WHERE channel_key = 'MARKETING'",
                Boolean.class))
        .isFalse();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM consent_notice_delivery", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void 수신_거부_링크는_로그인과_CSRF_없이_동작한다() throws Exception {
    String token = unsubscribeTokens.issue(userId);

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/public/marketing-unsubscribe")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ALREADY_UNSUBSCRIBED"));
  }

  @Test
  void 서명이_틀린_수신_거부_토큰은_400이다() throws Exception {
    assertFieldError(
        mockMvc.perform(
            jsonPost("/api/public/marketing-unsubscribe", "{\"token\":\"" + userId + ".forged\"}")),
        "token",
        "INVALID_VALUE");
    assertFieldError(
        mockMvc.perform(jsonPost("/api/public/marketing-unsubscribe", "{\"token\":\"garbage\"}")),
        "token",
        "INVALID_VALUE");
  }

  @Test
  void 수신_거부_호출이_몰리면_429다() throws Exception {
    for (int i = 0; i < 20; i++) {
      mockMvc.perform(jsonPost("/api/public/marketing-unsubscribe", "{\"token\":\"garbage\"}"));
    }

    assertError(
        mockMvc.perform(jsonPost("/api/public/marketing-unsubscribe", "{\"token\":\"garbage\"}")),
        ErrorCode.RATE_LIMITED);
  }
}
