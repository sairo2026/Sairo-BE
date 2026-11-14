package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.ApproverAccountTestBase;
import com.sairo.be.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@IntegrationTest
@TestPropertySource(properties = "sairo.delivery.sms.provider=none")
class ApproverAccountSmsUnavailableIntegrationTest extends ApproverAccountTestBase {

  @Test
  void 문자_수단이_없으면_승인자_계정_인증번호_발송은_목적과_관계없이_503으로_막힌다() throws Exception {
    RequestFixture f = requestFixture();
    String token = issueApproverToken(f.cookie(), f.propertyId(), f.approvalId());

    assertError(sendOtp("CLAIM", PHONE, token, null), ErrorCode.SMS_UNAVAILABLE);
    assertError(sendOtp("LOGIN", PHONE, null, null), ErrorCode.SMS_UNAVAILABLE);
    assertError(sendOtp("REAUTH", PHONE, null, null), ErrorCode.SMS_UNAVAILABLE);

    assertThat(smsSender.sent()).isEmpty();
    assertThat(redisTemplate.keys("sairo:approver-code:*")).isEmpty();
    assertThat(count("SELECT count(*) FROM approver_profile")).isZero();
  }

  @Test
  void 문자_수단이_없어도_승인자_링크로_신청을_비교하고_선택해_확정한다() throws Exception {
    RequestFixture f = requestFixture();
    long requestId = submit(f);
    String token = issueApproverToken(f.cookie(), f.propertyId(), f.approvalId());

    selectNow(token, requestId).andExpect(status().isOk());
  }
}
