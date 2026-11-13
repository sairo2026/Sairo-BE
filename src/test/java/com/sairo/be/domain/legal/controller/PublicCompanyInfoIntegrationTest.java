package com.sairo.be.domain.legal.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import org.junit.jupiter.api.Test;

@IntegrationTest
class PublicCompanyInfoIntegrationTest extends AuthIntegrationTestBase {

  private static final String URL = "/api/public/company-info";

  private long operator() {
    return jdbcTemplate.queryForObject(
        "INSERT INTO operator_account (kakao_provider_key, name, status, mfa_enabled,"
            + " totp_secret_encrypted) VALUES ('company-info-op', '운영자', 'ACTIVE', true, 'x')"
            + " RETURNING id",
        Long.class);
  }

  private void insertPublished(
      int versionNo, String name, String ecommerceNotice, long operatorId) {
    jdbcTemplate.update(
        "INSERT INTO company_service_info (version_no, business_name, representative_name,"
            + " business_number, address, contact_phone, ecommerce_notice, support_email,"
            + " support_hours, emergency_contact, privacy_officer, privacy_contact,"
            + " privacy_request_path, cancellation_path, inquiry_delivery_tested_at,"
            + " reviewed_by_operator_id, published_at) VALUES (?, ?, '김태환', '704-16-02872',"
            + " '주소', '031-000-0000', ?, 'help@example.com', '평일', '긴급', '책임자',"
            + " '개인정보 문의', '/privacy', '/cancel', now(), ?, now() - interval '1 hour')",
        versionNo,
        name,
        ecommerceNotice,
        operatorId);
  }

  private void insertUnpublished(int versionNo, String name) {
    jdbcTemplate.update(
        "INSERT INTO company_service_info (version_no, business_name, representative_name,"
            + " business_number, address, contact_phone, support_email, support_hours,"
            + " emergency_contact, privacy_officer, privacy_contact, privacy_request_path,"
            + " cancellation_path) VALUES (?, ?, '김태환', '704-16-02872', '주소', '031-000-0000',"
            + " 'help@example.com', '평일', '긴급', '책임자', '개인정보 문의', '/privacy', '/cancel')",
        versionNo,
        name);
  }

  @Test
  void 게시된_회사_정보가_없으면_설정의_사업자_정보를_로그인_없이_캐시_헤더와_함께_준다() throws Exception {
    mockMvc
        .perform(get(URL))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", containsString("max-age=300")))
        .andExpect(header().string("Cache-Control", containsString("public")))
        .andExpect(jsonPath("$.businessName").value("사이로(SAIRO)"))
        .andExpect(jsonPath("$.representativeName").value("김태환"))
        .andExpect(jsonPath("$.businessNumber").value("704-16-02872"))
        .andExpect(jsonPath("$.address").value("경기도 고양시 덕양구 백양로 51, 902호 A-5(화정동, 네이버타운)"))
        .andExpect(jsonPath("$", hasKey("ecommerceNotice")))
        .andExpect(jsonPath("$.ecommerceNotice").value(nullValue()))
        .andExpect(jsonPath("$", hasKey("contactPhone")))
        .andExpect(jsonPath("$.contactPhone").value(nullValue()))
        .andExpect(jsonPath("$", hasKey("supportEmail")))
        .andExpect(jsonPath("$.supportEmail").value(nullValue()))
        .andExpect(jsonPath("$", hasKey("privacyContact")))
        .andExpect(jsonPath("$", hasKey("privacyRequestPath")))
        .andExpect(jsonPath("$", hasKey("versionNo")))
        .andExpect(jsonPath("$.versionNo").value(nullValue()))
        .andExpect(jsonPath("$", hasKey("publishedAt")))
        .andExpect(jsonPath("$.publishedAt").value(nullValue()))
        .andExpect(jsonPath("$.links.length()").value(3))
        .andExpect(jsonPath("$.links[0].kind").value("TERMS"))
        .andExpect(jsonPath("$.links[0].title").value("이용약관"))
        .andExpect(jsonPath("$.links[0].url").value("/legal/policies/TERMS/1.0"))
        .andExpect(jsonPath("$.links[1].url").value("/legal/policies/PRIVACY/1.0"))
        .andExpect(jsonPath("$.links[2].url").value("/legal/policies/REFUND/1.0"));
  }

  @Test
  void 게시된_최신_버전이_있으면_그_값을_주고_미게시_버전은_무시한다() throws Exception {
    long operatorId = operator();
    insertPublished(3, "이전 상호", null, operatorId);
    insertPublished(4, "최신 상호", "제2026-고양덕양-0000호", operatorId);
    insertUnpublished(5, "미게시 상호");

    mockMvc
        .perform(get(URL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.versionNo").value(4))
        .andExpect(jsonPath("$.businessName").value("최신 상호"))
        .andExpect(jsonPath("$.ecommerceNotice").value("제2026-고양덕양-0000호"))
        .andExpect(jsonPath("$.contactPhone").value("031-000-0000"))
        .andExpect(jsonPath("$.supportEmail").value("help@example.com"))
        .andExpect(jsonPath("$.privacyContact").value("개인정보 문의"))
        .andExpect(jsonPath("$.privacyRequestPath").value("/privacy"))
        .andExpect(jsonPath("$.publishedAt").exists());
  }

  @Test
  void 게시_중인_정책_문서의_최신_버전으로_링크를_만든다() throws Exception {
    long operatorId = operator();
    jdbcTemplate.update(
        "INSERT INTO policy_document (doc_type, version, status, body, change_summary,"
            + " requires_consent, effective_at, published_at, created_by_operator_id,"
            + " reviewed_by_operator_id, published_by_operator_id) VALUES ('TERMS', '2.0',"
            + " 'PUBLISHED', '본문', '요약', false, now() - interval '1 day',"
            + " now() - interval '1 day', ?, ?, ?)",
        operatorId,
        operatorId,
        operatorId);

    mockMvc
        .perform(get(URL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.links[0].url").value("/legal/policies/TERMS/2.0"))
        .andExpect(jsonPath("$.links[1].url").value("/legal/policies/PRIVACY/1.0"));
  }

  @Test
  void 같은_IP는_분당_60회까지만_조회하고_넘으면_429다() throws Exception {
    for (int i = 0; i < 60; i++) {
      mockMvc.perform(get(URL)).andExpect(status().isOk());
    }

    com.sairo.be.support.ErrorResponseAssertions.assertError(
        mockMvc.perform(get(URL)), com.sairo.be.global.error.ErrorCode.RATE_LIMITED);
  }
}
