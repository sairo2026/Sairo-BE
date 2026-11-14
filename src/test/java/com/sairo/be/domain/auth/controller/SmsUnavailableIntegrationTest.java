package com.sairo.be.domain.auth.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@IntegrationTest
@TestPropertySource(properties = "sairo.delivery.sms.provider=none")
class SmsUnavailableIntegrationTest extends AuthIntegrationTestBase {

  @Test
  void 문자_공급자가_없으면_약관_목록이_인증번호_본인확인을_필수로_알리지_않는다() throws Exception {
    mockMvc
        .perform(get("/api/terms"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.phoneVerificationRequired").value(false));
  }

  @Test
  void 문자_공급자가_없으면_인증번호_발송은_503으로_막고_문자를_보내지_않는다() throws Exception {
    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/auth/phone-verification",
                "{\"phone\":\"010-1234-5678\",\"purpose\":\"SIGNUP\"}")),
        ErrorCode.SMS_UNAVAILABLE);

    assertThat(smsSender.sent()).isEmpty();
    assertThat(redisTemplate.keys("sairo:phone-code:*")).isEmpty();
  }
}
