package com.sairo.be.domain.legal.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@IntegrationTest
class PolicyDocumentIntegrationTest extends AuthIntegrationTestBase {

  private static final String URL = "/api/policy-documents";

  private long operatorId;

  @BeforeEach
  void createOperator() {
    operatorId =
        jdbcTemplate.queryForObject(
            "INSERT INTO operator_account (kakao_provider_key, name, status, mfa_enabled,"
                + " totp_secret_encrypted) VALUES ('policy-op', '운영자', 'ACTIVE', true, 'x')"
                + " RETURNING id",
            Long.class);
  }

  private void insertDocument(
      String docType, String version, String status, String effective, boolean requiresConsent) {
    boolean published = "PUBLISHED".equals(status) || "ENDED".equals(status);
    jdbcTemplate.update(
        "INSERT INTO policy_document (doc_type, version, status, body, change_summary,"
            + " requires_consent, effective_at, published_at, created_by_operator_id,"
            + " reviewed_by_operator_id, published_by_operator_id) VALUES (?, ?, ?, ?, '요약', ?,"
            + " now() + (?::text)::interval, now() + (?::text)::interval, ?, ?, ?)",
        docType,
        version,
        status,
        docType + " " + version + " 본문",
        requiresConsent,
        effective,
        published ? effective : "-1 day",
        operatorId,
        published ? operatorId : null,
        published ? operatorId : null);
  }

  @Test
  void 로그인_없이_문서_종류마다_시행_중인_최신_버전_하나를_본문과_함께_돌려준다() throws Exception {
    insertDocument("TERMS", "1.0", "PUBLISHED", "-10 days", true);
    insertDocument("TERMS", "2.0", "PUBLISHED", "-1 day", true);
    insertDocument("PRIVACY", "1.0", "PUBLISHED", "-3 days", false);
    insertDocument("SUBPROCESSORS", "2026-09-01", "PUBLISHED", "-2 days", false);

    mockMvc
        .perform(get(URL))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", containsString("max-age=300")))
        .andExpect(jsonPath("$.items.length()").value(3))
        .andExpect(jsonPath("$.items[0].docType").value("TERMS"))
        .andExpect(jsonPath("$.items[0].version").value("2.0"))
        .andExpect(jsonPath("$.items[0].requiresConsent").value(true))
        .andExpect(jsonPath("$.items[0].body").value("TERMS 2.0 본문"))
        .andExpect(jsonPath("$.items[0].effectiveAt").exists())
        .andExpect(jsonPath("$.items[1].docType").value("PRIVACY"))
        .andExpect(jsonPath("$.items[1].requiresConsent").value(false))
        .andExpect(jsonPath("$.items[2].docType").value("SUBPROCESSORS"))
        .andExpect(jsonPath("$.items[2].body").value("SUBPROCESSORS 2026-09-01 본문"));
  }

  @Test
  void 시행_전과_종료와_미게시_문서는_돌려주지_않는다() throws Exception {
    insertDocument("TERMS", "1.0", "PUBLISHED", "-10 days", true);
    insertDocument("TERMS", "2.0", "PUBLISHED", "5 days", true);
    insertDocument("PRIVACY", "1.0", "ENDED", "-30 days", false);
    insertDocument("REFUND", "1.0", "DRAFT", "-1 day", false);
    insertDocument("PRICING", "1.0", "SCHEDULED", "-1 day", false);

    mockMvc
        .perform(get(URL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].docType").value("TERMS"))
        .andExpect(jsonPath("$.items[0].version").value("1.0"));
  }

  @Test
  void 시행_중인_문서가_없으면_빈_배열이다() throws Exception {
    mockMvc.perform(get(URL)).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
  }

  @Test
  void 같은_IP는_분당_60회까지만_조회하고_넘으면_429다() throws Exception {
    for (int i = 0; i < 60; i++) {
      mockMvc.perform(get(URL)).andExpect(status().isOk());
    }

    assertError(mockMvc.perform(get(URL)), ErrorCode.RATE_LIMITED);
  }
}
