package com.sairo.be.domain.legal.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@IntegrationTest
@TestPropertySource(properties = "sairo.company.business-number=")
class PublicCompanyInfoUnpublishedIntegrationTest extends AuthIntegrationTestBase {

  @Test
  void 게시된_정보도_완성된_설정도_없으면_404_COMPANY_INFO_NOT_PUBLISHED다() throws Exception {
    assertError(
        mockMvc.perform(get("/api/public/company-info")), ErrorCode.COMPANY_INFO_NOT_PUBLISHED);
  }
}
