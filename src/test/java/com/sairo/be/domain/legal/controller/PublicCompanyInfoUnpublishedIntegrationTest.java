package com.sairo.be.domain.legal.controller;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@IntegrationTest
@TestPropertySource(properties = "sairo.company.business-number=")
class PublicCompanyInfoUnpublishedIntegrationTest extends AuthIntegrationTestBase {

  @Test
  void 게시된_정보가_없고_설정이_비어_있어도_200이고_빈_항목은_키를_남긴_채_null이다() throws Exception {
    mockMvc
        .perform(get("/api/public/company-info"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasKey("businessNumber")))
        .andExpect(jsonPath("$.businessNumber").value(nullValue()))
        .andExpect(jsonPath("$.businessName").value("사이로(SAIRO)"))
        .andExpect(jsonPath("$", hasKey("versionNo")))
        .andExpect(jsonPath("$.versionNo").value(nullValue()))
        .andExpect(jsonPath("$", hasKey("publishedAt")))
        .andExpect(jsonPath("$.publishedAt").value(nullValue()))
        .andExpect(jsonPath("$.links.length()").value(3));
  }
}
