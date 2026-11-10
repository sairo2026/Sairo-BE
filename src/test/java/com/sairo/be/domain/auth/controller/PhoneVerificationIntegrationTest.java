package com.sairo.be.domain.auth.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.auth.service.AuthRateLimits;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.support.RequestRateLimiter;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class PhoneVerificationIntegrationTest extends AuthIntegrationTestBase {

  private static final String PHONE = "010-1234-5678";

  @Autowired private RequestRateLimiter rateLimiter;

  private String sendBody(String phone, String purpose) {
    return "{\"phone\":\"" + phone + "\",\"purpose\":\"" + purpose + "\"}";
  }

  private String verifyBody(String phone, String purpose, String code) {
    return "{\"phone\":\""
        + phone
        + "\",\"purpose\":\""
        + purpose
        + "\",\"code\":\""
        + code
        + "\"}";
  }

  private void send(String phone) throws Exception {
    mockMvc.perform(jsonPost("/api/auth/phone-verification", sendBody(phone, "SIGNUP")));
  }

  private Set<String> redisKeys() {
    return redisTemplate.keys("sairo:phone-code:*");
  }

  @Test
  void 인증번호_발송은_202이고_6자리_숫자를_문자로_보낸다() throws Exception {
    mockMvc
        .perform(jsonPost("/api/auth/phone-verification", sendBody(PHONE, "SIGNUP")))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.accepted").value(true))
        .andExpect(jsonPath("$.resendAfterSeconds").value(60));

    assertThat(flow.lastSmsCode(PHONE)).matches("\\d{6}");
  }

  @Test
  void 번호는_하이픈_없이_보내도_같은_형식으로_정규화한다() throws Exception {
    mockMvc.perform(jsonPost("/api/auth/phone-verification", sendBody("01012345678", "SIGNUP")));

    assertThat(flow.lastSmsCode(PHONE)).matches("\\d{6}");
  }

  @Test
  void 인증번호_원문은_저장소에_남기지_않고_해시만_둔다() throws Exception {
    send(PHONE);
    String code = flow.lastSmsCode(PHONE);

    String key = redisKeys().iterator().next();
    var state = redisTemplate.opsForHash().entries(key);
    assertThat(state.values()).noneMatch(value -> value.toString().contains(code));
    assertThat((String) state.get("codeHash")).hasSize(64);
    assertThat(redisTemplate.getExpire(key)).isPositive().isLessThanOrEqualTo(15 * 60);
  }

  @Test
  void 형식이_틀린_번호는_INVALID_REQUEST다() throws Exception {
    assertFieldError(
        mockMvc.perform(
            jsonPost("/api/auth/phone-verification", sendBody("02-123-4567", "SIGNUP"))),
        "phone",
        "INVALID_FORMAT");
  }

  @Test
  void 목적이_없으면_INVALID_REQUEST다() throws Exception {
    assertFieldError(
        mockMvc.perform(jsonPost("/api/auth/phone-verification", "{\"phone\":\"" + PHONE + "\"}")),
        "purpose",
        "REQUIRED");
  }

  @Test
  void 발송_간격_60초_안의_재요청은_429다() throws Exception {
    send(PHONE);

    assertError(
            mockMvc.perform(jsonPost("/api/auth/phone-verification", sendBody(PHONE, "SIGNUP"))),
            ErrorCode.RATE_LIMITED)
        .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
  }

  @Test
  void 같은_번호는_시간당_5회까지_허용하고_6번째는_429다() throws Exception {
    for (int i = 0; i < 5; i++) {
      rateLimiter.reset(AuthRateLimits.PHONE_SEND_INTERVAL, PHONE);
      mockMvc
          .perform(jsonPost("/api/auth/phone-verification", sendBody(PHONE, "SIGNUP")))
          .andExpect(status().isAccepted());
    }
    rateLimiter.reset(AuthRateLimits.PHONE_SEND_INTERVAL, PHONE);

    assertError(
        mockMvc.perform(jsonPost("/api/auth/phone-verification", sendBody(PHONE, "SIGNUP"))),
        ErrorCode.RATE_LIMITED);
  }

  @Test
  void 같은_IP는_시간당_20회까지_허용하고_21번째는_429다() throws Exception {
    for (int i = 0; i < 20; i++) {
      String phone = String.format("010-5000-%04d", i);
      mockMvc
          .perform(jsonPost("/api/auth/phone-verification", sendBody(phone, "SIGNUP")))
          .andExpect(status().isAccepted());
    }

    assertError(
        mockMvc.perform(
            jsonPost("/api/auth/phone-verification", sendBody("010-6000-0000", "SIGNUP"))),
        ErrorCode.RATE_LIMITED);
  }

  @Test
  void 올바른_인증번호는_티켓과_만료_시각을_돌려주고_번호를_소진한다() throws Exception {
    send(PHONE);
    String code = flow.lastSmsCode(PHONE);

    mockMvc
        .perform(jsonPost("/api/auth/phone-verification/verify", verifyBody(PHONE, "SIGNUP", code)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verifiedPhoneTicket").isNotEmpty())
        .andExpect(jsonPath("$.expiresAt").isNotEmpty());

    assertError(
        mockMvc.perform(
            jsonPost("/api/auth/phone-verification/verify", verifyBody(PHONE, "SIGNUP", code))),
        ErrorCode.TOKEN_INVALID);
  }

  @Test
  void 틀린_인증번호는_남은_횟수를_알리고_5번째_오입력에서_폐기한다() throws Exception {
    send(PHONE);
    String code = flow.lastSmsCode(PHONE);
    String wrong = code.equals("000000") ? "111111" : "000000";

    for (int remaining = 4; remaining >= 1; remaining--) {
      assertError(
              mockMvc.perform(
                  jsonPost(
                      "/api/auth/phone-verification/verify", verifyBody(PHONE, "SIGNUP", wrong))),
              ErrorCode.VERIFICATION_CODE_INVALID)
          .andExpect(jsonPath("$.remainingAttempts").value(remaining));
    }
    assertError(
            mockMvc.perform(
                jsonPost(
                    "/api/auth/phone-verification/verify", verifyBody(PHONE, "SIGNUP", wrong))),
            ErrorCode.VERIFICATION_CODE_INVALID)
        .andExpect(jsonPath("$.remainingAttempts").value(0));

    assertError(
        mockMvc.perform(
            jsonPost("/api/auth/phone-verification/verify", verifyBody(PHONE, "SIGNUP", code))),
        ErrorCode.TOKEN_INVALID);
  }

  @Test
  void 발송_이력이_없으면_TOKEN_INVALID다() throws Exception {
    assertError(
        mockMvc.perform(
            jsonPost("/api/auth/phone-verification/verify", verifyBody(PHONE, "SIGNUP", "123456"))),
        ErrorCode.TOKEN_INVALID);
  }

  @Test
  void 발송_후_5분이_지나면_TOKEN_EXPIRED다() throws Exception {
    send(PHONE);
    String code = flow.lastSmsCode(PHONE);
    String key = redisKeys().iterator().next();
    redisTemplate
        .opsForHash()
        .put(key, "issuedAt", String.valueOf(Instant.now().minusSeconds(301).getEpochSecond()));

    assertError(
        mockMvc.perform(
            jsonPost("/api/auth/phone-verification/verify", verifyBody(PHONE, "SIGNUP", code))),
        ErrorCode.TOKEN_EXPIRED);
  }

  @Test
  void 같은_번호로_재발송하면_이전_인증번호는_무효다() throws Exception {
    send(PHONE);
    String first = flow.lastSmsCode(PHONE);
    rateLimiter.reset(AuthRateLimits.PHONE_SEND_INTERVAL, PHONE);
    send(PHONE);
    String second = flow.lastSmsCode(PHONE);
    if (first.equals(second)) {
      return;
    }

    assertError(
        mockMvc.perform(
            jsonPost("/api/auth/phone-verification/verify", verifyBody(PHONE, "SIGNUP", first))),
        ErrorCode.VERIFICATION_CODE_INVALID);
    mockMvc
        .perform(
            jsonPost("/api/auth/phone-verification/verify", verifyBody(PHONE, "SIGNUP", second)))
        .andExpect(status().isOk());
  }

  @Test
  void 인증번호는_6자리_숫자가_아니면_INVALID_REQUEST다() throws Exception {
    assertFieldError(
        mockMvc.perform(
            jsonPost("/api/auth/phone-verification/verify", verifyBody(PHONE, "SIGNUP", "12ab56"))),
        "code",
        "INVALID_FORMAT");
  }

  @Test
  void 휴대폰_변경_목적은_로그인_세션이_없으면_발송과_확인_모두_401이다() throws Exception {
    assertError(
        mockMvc.perform(jsonPost("/api/auth/phone-verification", sendBody(PHONE, "CHANGE"))),
        ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(
            jsonPost("/api/auth/phone-verification/verify", verifyBody(PHONE, "CHANGE", "123456"))),
        ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 휴대폰_변경_목적은_로그인한_본인만_확인할_수_있다() throws Exception {
    saveEmailUser("owner@example.com", "010-0000-1111");
    saveEmailUser("other@example.com", "010-0000-2222");
    Cookie owner = login("owner@example.com", PASSWORD);
    Cookie other = login("other@example.com", PASSWORD);

    mockMvc
        .perform(jsonPost("/api/auth/phone-verification", sendBody(PHONE, "CHANGE"), owner))
        .andExpect(status().isAccepted());
    String code = flow.lastSmsCode(PHONE);

    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/auth/phone-verification/verify", verifyBody(PHONE, "CHANGE", code), other)),
        ErrorCode.TOKEN_INVALID);
    mockMvc
        .perform(
            jsonPost(
                "/api/auth/phone-verification/verify", verifyBody(PHONE, "CHANGE", code), owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verifiedPhoneTicket").isNotEmpty());
  }

  @Test
  void 가입_목적과_변경_목적의_인증번호는_서로_쓸_수_없다() throws Exception {
    send(PHONE);
    String code = flow.lastSmsCode(PHONE);
    saveEmailUser("owner@example.com", "010-0000-1111");
    Cookie owner = login("owner@example.com", PASSWORD);

    assertError(
        mockMvc.perform(
            jsonPost(
                "/api/auth/phone-verification/verify", verifyBody(PHONE, "CHANGE", code), owner)),
        ErrorCode.TOKEN_INVALID);
  }

  @Test
  void 쿠키_없는_변경_요청은_CSRF_검증에_걸린다() throws Exception {
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/auth/phone-verification")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(sendBody(PHONE, "SIGNUP")))
        .andExpect(status().isForbidden());
    assertThat(AuthFlowSupport.CSRF_VALUE).isNotBlank();
  }
}
