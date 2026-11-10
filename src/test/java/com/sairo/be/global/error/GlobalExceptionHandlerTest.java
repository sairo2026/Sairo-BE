package com.sairo.be.global.error;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.support.PlainText;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

  private static final String UUID_PATTERN =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mockMvc =
        MockMvcBuilders.standaloneSetup(new Probe())
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(validator)
            .build();
  }

  @Test
  void 업무_예외는_코드_메시지_추적ID만_담은_문서_형식으로_응답한다() throws Exception {
    mockMvc
        .perform(get("/business").param("code", "RESOURCE_NOT_FOUND"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("요청한 리소스를 찾을 수 없습니다."))
        .andExpect(jsonPath("$.traceId").value(matchesPattern(UUID_PATTERN)))
        .andExpect(jsonPath("$.fieldErrors").doesNotExist())
        .andExpect(jsonPath("$.instance").doesNotExist())
        .andExpect(jsonPath("$.title").doesNotExist());
  }

  @Test
  void 요청_제한_응답은_retryAfterSeconds를_본문과_헤더에_담는다() throws Exception {
    mockMvc
        .perform(get("/rate-limited"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "30"))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
        .andExpect(jsonPath("$.retryAfterSeconds").value(30));
  }

  @Test
  void 구성원_신청_한도_초과는_한도_종류_한도_현재_건수를_담는다() throws Exception {
    mockMvc
        .perform(get("/quota").param("scope", "MEMBERSHIP"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("VISIT_REQUEST_QUOTA_EXCEEDED"))
        .andExpect(jsonPath("$.retryAfterSeconds").value(60))
        .andExpect(jsonPath("$.quotaScope").value("MEMBERSHIP"))
        .andExpect(jsonPath("$.limit").value(40))
        .andExpect(jsonPath("$.current").value(40));
  }

  @Test
  void 출입승인_신청_한도_초과는_다른_사무소_건수를_알리지_않도록_current가_null이다() throws Exception {
    mockMvc
        .perform(get("/quota").param("scope", "APPROVAL"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.quotaScope").value("APPROVAL"))
        .andExpect(jsonPath("$.limit").value(10))
        .andExpect(jsonPath("$.current").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(
            result ->
                org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getContentAsString())
                    .contains("\"current\":null"));
  }

  @Test
  void 버전_충돌은_currentVersion을_담는다() throws Exception {
    mockMvc
        .perform(get("/version-conflict"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
        .andExpect(jsonPath("$.currentVersion").value(7));
  }

  @Test
  void 약관_버전_변경은_changedItems를_담는다() throws Exception {
    mockMvc
        .perform(get("/terms-changed"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TERMS_VERSION_CHANGED"))
        .andExpect(jsonPath("$.changedItems[0]").value("SERVICE_TERMS"));
  }

  @Test
  void 인증번호_오입력은_remainingAttempts를_담는다() throws Exception {
    mockMvc
        .perform(get("/wrong-code"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("VERIFICATION_CODE_INVALID"))
        .andExpect(jsonPath("$.remainingAttempts").value(3));
  }

  @Test
  void 제한_안내는_actionUrl을_담는다() throws Exception {
    mockMvc
        .perform(get("/action-url"))
        .andExpect(status().isPaymentRequired())
        .andExpect(jsonPath("$.code").value("PAYMENT_METHOD_REQUIRED"))
        .andExpect(jsonPath("$.actionUrl").value("/settings/billing"));
  }

  @Test
  void 승인자_조건_위반은_violatedRules를_담는다() throws Exception {
    mockMvc
        .perform(get("/violated"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.violatedRules[0]").value("MIN_LEAD_TIME"));
  }

  @Test
  void 필드_검증_실패는_field_code_message를_담은_fieldErrors로_응답한다() throws Exception {
    mockMvc
        .perform(
            post("/validated")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"\",\"memo\":\"" + "가".repeat(11) + "\",\"count\":0}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.traceId").value(matchesPattern(UUID_PATTERN)))
        .andExpect(jsonPath("$.fieldErrors", hasSize(3)))
        .andExpect(jsonPath("$.fieldErrors[?(@.field == 'title')].code").value("REQUIRED"))
        .andExpect(jsonPath("$.fieldErrors[?(@.field == 'memo')].code").value("TOO_LONG"))
        .andExpect(jsonPath("$.fieldErrors[?(@.field == 'count')].code").value("OUT_OF_RANGE"));
  }

  @Test
  void 공백만_있는_이름과_HTML이_든_이름은_서로_다른_필드_오류_코드를_받는다() throws Exception {
    mockMvc
        .perform(
            post("/validated")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"   \",\"memo\":\"<b>굵게</b>\",\"count\":1}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[?(@.field == 'title')].code").value("REQUIRED"))
        .andExpect(jsonPath("$.fieldErrors[?(@.field == 'memo')].code").value("HTML_NOT_ALLOWED"));
  }

  @Test
  void 앞뒤_공백을_제외한_길이로_최소_글자_수를_검증한다() throws Exception {
    mockMvc
        .perform(
            post("/min-length")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\" 가 \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].code").value("TOO_SHORT"));
  }

  @Test
  void 필수_파라미터_누락은_REQUIRED_필드_오류다() throws Exception {
    mockMvc
        .perform(get("/required-param"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("keyword"))
        .andExpect(jsonPath("$.fieldErrors[0].code").value("REQUIRED"));
  }

  @Test
  void 파라미터_형식_오류는_TYPE_MISMATCH_필드_오류다() throws Exception {
    mockMvc
        .perform(get("/typed").param("count", "abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("count"))
        .andExpect(jsonPath("$.fieldErrors[0].code").value("TYPE_MISMATCH"));
  }

  @Test
  void 깨진_JSON은_필드_오류_없이_INVALID_REQUEST로_응답한다() throws Exception {
    mockMvc
        .perform(post("/validated").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.fieldErrors").doesNotExist());
  }

  @Test
  void 지원하지_않는_메서드는_문서_형식의_오류_본문을_가진다() throws Exception {
    mockMvc
        .perform(post("/rate-limited"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.traceId").value(matchesPattern(UUID_PATTERN)));
  }

  @Test
  void 인증_예외는_401_UNAUTHENTICATED이고_권한_예외는_403_FORBIDDEN이다() throws Exception {
    mockMvc
        .perform(get("/authentication-failure"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    mockMvc
        .perform(get("/access-denied"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void 예상하지_못한_예외는_내부_정보를_숨기고_500으로_응답한다() throws Exception {
    mockMvc
        .perform(get("/unexpected"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.message").value("일시적인 오류가 발생했습니다."))
        .andExpect(jsonPath("$.traceId").value(matchesPattern(UUID_PATTERN)))
        .andExpect(
            result ->
                org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getContentAsString())
                    .doesNotContain("SECRET_INTERNAL_DETAIL")
                    .doesNotContain("stackTrace"));
  }

  @Test
  void 응답_본문에_ProblemDetail_필드가_없다() throws Exception {
    mockMvc
        .perform(get("/business").param("code", "FORBIDDEN"))
        .andExpect(jsonPath("$.type").doesNotExist())
        .andExpect(jsonPath("$.detail").doesNotExist())
        .andExpect(
            result ->
                org.hamcrest.MatcherAssert.assertThat(
                    result.getResponse().getContentAsString(), not(containsString("about:blank"))));
  }

  public record ValidatedRequest(
      @PlainText(min = 1, max = 10) String title,
      @PlainText(min = 0, max = 10) String memo,
      @Min(1) @Max(5) int count) {}

  public record MinLengthRequest(@PlainText(min = 2, max = 10) String name) {}

  @RestController
  static class Probe {

    @GetMapping("/business")
    void business(@RequestParam ErrorCode code) {
      throw new BusinessException(code);
    }

    @GetMapping("/rate-limited")
    void rateLimited() {
      throw BusinessException.rateLimited(30);
    }

    @GetMapping("/quota")
    void quota(@RequestParam BusinessException.QuotaScope scope) {
      if (scope == BusinessException.QuotaScope.MEMBERSHIP) {
        throw BusinessException.visitRequestQuotaExceeded(60, scope, 40, 40L);
      }
      throw BusinessException.visitRequestQuotaExceeded(60, scope, 10, 10L);
    }

    @GetMapping("/version-conflict")
    void versionConflict() {
      throw BusinessException.versionConflict(7);
    }

    @GetMapping("/terms-changed")
    void termsChanged() {
      throw BusinessException.termsVersionChanged(List.of("SERVICE_TERMS"));
    }

    @GetMapping("/wrong-code")
    void wrongCode() {
      throw BusinessException.verificationCodeInvalid(3);
    }

    @GetMapping("/action-url")
    void actionUrl() {
      throw BusinessException.withActionUrl(ErrorCode.PAYMENT_METHOD_REQUIRED, "/settings/billing");
    }

    @GetMapping("/violated")
    void violated() {
      throw BusinessException.approverConditionViolated(List.of("MIN_LEAD_TIME"));
    }

    @PostMapping("/validated")
    void validated(@Valid @RequestBody ValidatedRequest request) {}

    @PostMapping("/min-length")
    void minLength(@Valid @RequestBody MinLengthRequest request) {}

    @GetMapping("/required-param")
    void requiredParam(@RequestParam String keyword) {}

    @GetMapping("/typed")
    void typed(@RequestParam int count) {}

    @GetMapping("/authentication-failure")
    void authenticationFailure() {
      throw new BadCredentialsException("bad");
    }

    @GetMapping("/access-denied")
    void accessDenied() {
      throw new AccessDeniedException("denied");
    }

    @GetMapping("/unexpected")
    void unexpected() {
      throw new IllegalStateException("SECRET_INTERNAL_DETAIL");
    }
  }
}
