package com.sairo.be.domain.auth.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@IntegrationTest
class TermsHistoryIntegrationTest extends AuthIntegrationTestBase {

  private Long userId;
  private Cookie session;

  @BeforeEach
  void createUser() throws Exception {
    userId = saveEmailUser("agent@example.com", null).getId();
    session = login("agent@example.com", PASSWORD);
  }

  private void insertAgreement(String code, boolean agreed, String path, String at) {
    jdbcTemplate.update(
        "INSERT INTO terms_agreement (user_id, terms_code, terms_version, agreed, consent_path,"
            + " recorded_at, office_id) VALUES (?, ?, '2026-09-01', ?, ?, ?::timestamptz, ?)",
        userId,
        code,
        agreed,
        path,
        at,
        path.equals("PURCHASE_CONSENT") || path.equals("CARD_REGISTRATION") ? officeId() : null);
  }

  private Long officeId() {
    return jdbcTemplate.queryForList("SELECT id FROM office", Long.class).stream()
        .findFirst()
        .orElseGet(
            () ->
                jdbcTemplate.queryForObject(
                    "INSERT INTO office (name, entity_type, registration_authority,"
                        + " real_estate_license_number, business_registration_number,"
                        + " representative_name, representative_phone, address)"
                        + " VALUES ('사무소', 'INDIVIDUAL', '서울', 'L-1', '1234567890', '홍', '010', '서울')"
                        + " RETURNING id",
                    Long.class));
  }

  @Test
  void 동의_이력은_최신순으로_경로와_표시_이름을_담는다() throws Exception {
    insertAgreement("TOS", true, "SIGNUP_EMAIL", "2026-09-02T00:00:00Z");
    insertAgreement("MARKETING", true, "ACCOUNT_SETTINGS", "2026-09-03T00:00:00Z");
    insertAgreement("MARKETING", false, "UNSUBSCRIBE_LINK", "2026-09-04T00:00:00Z");

    mockMvc
        .perform(get("/api/me/terms-agreements").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(25))
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.items[0].termsCode").value("MARKETING"))
        .andExpect(jsonPath("$.items[0].termsName").value("마케팅 정보 수신 동의"))
        .andExpect(jsonPath("$.items[0].version").value("2026-09-01"))
        .andExpect(jsonPath("$.items[0].agreed").value(false))
        .andExpect(jsonPath("$.items[0].consentPath").value("UNSUBSCRIBE_LINK"))
        .andExpect(jsonPath("$.items[0].agreementId").isNumber())
        .andExpect(jsonPath("$.items[0].recordedAt").isNotEmpty())
        .andExpect(jsonPath("$.items[2].termsCode").value("TOS"))
        .andExpect(jsonPath("$.items[2].consentPath").value("SIGNUP_EMAIL"));
  }

  @Test
  void 구독_구매_동의와_카드_등록_확인은_이력에서_제외한다() throws Exception {
    insertAgreement("TOS", true, "SIGNUP_EMAIL", "2026-09-02T00:00:00Z");
    insertAgreement("TOS", true, "PURCHASE_CONSENT", "2026-09-03T00:00:00Z");
    insertAgreement("TOS", true, "CARD_REGISTRATION", "2026-09-04T00:00:00Z");

    mockMvc
        .perform(get("/api/me/terms-agreements").cookie(session))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].consentPath").value("SIGNUP_EMAIL"));
  }

  @Test
  void 약관_코드로_거르고_페이지로_나눈다() throws Exception {
    for (int i = 0; i < 30; i++) {
      insertAgreement("MARKETING", i % 2 == 0, "ACCOUNT_SETTINGS", "2026-09-02T00:00:00Z");
    }
    insertAgreement("TOS", true, "SIGNUP_EMAIL", "2026-09-02T00:00:00Z");

    mockMvc
        .perform(get("/api/me/terms-agreements").cookie(session).param("termsCode", "MARKETING"))
        .andExpect(jsonPath("$.totalElements").value(30))
        .andExpect(jsonPath("$.items.length()").value(25));
    mockMvc
        .perform(
            get("/api/me/terms-agreements")
                .cookie(session)
                .param("termsCode", "MARKETING")
                .param("page", "1")
                .param("size", "25"))
        .andExpect(jsonPath("$.items.length()").value(5));
  }

  @Test
  void 다른_회원의_이력은_보이지_않는다() throws Exception {
    insertAgreement("TOS", true, "SIGNUP_EMAIL", "2026-09-02T00:00:00Z");
    saveEmailUser("other@example.com", null);
    Cookie other = login("other@example.com", PASSWORD);

    mockMvc
        .perform(get("/api/me/terms-agreements").cookie(other))
        .andExpect(jsonPath("$.totalElements").value(0))
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  @Test
  void 허용되지_않는_페이지_크기는_400이고_로그인이_필요하다() throws Exception {
    assertFieldError(
        mockMvc.perform(get("/api/me/terms-agreements").cookie(session).param("size", "10")),
        "size",
        "NOT_ALLOWED");
    assertError(mockMvc.perform(get("/api/me/terms-agreements")), ErrorCode.UNAUTHENTICATED);
  }
}
