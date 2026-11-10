package com.sairo.be.global.security.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffAuthentication;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StaffRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class ReconsentInterceptorTest {

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private ReconsentInterceptor interceptor(boolean required) {
    return new ReconsentInterceptor(userId -> required);
  }

  private void loginAsMember() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new StaffAuthentication(new StaffPrincipal(1L, 2L, 3L, "회원", StaffRole.AGENT)));
  }

  @Test
  void 면제_엔드포인트는_표의_17개다() {
    assertThat(ReconsentInterceptor.exemptionCount()).isEqualTo(17);
  }

  @ParameterizedTest
  @CsvSource({
    "GET,/api/auth/context",
    "GET,/api/me/session",
    "GET,/api/me/terms-reconsent",
    "POST,/api/me/terms-agreements",
    "GET,/api/me/terms-agreements",
    "POST,/api/auth/logout",
    "GET,/api/me/withdrawal-preview",
    "DELETE,/api/me",
    "POST,/api/privacy-requests",
    "GET,/api/privacy-requests/me",
    "GET,/api/privacy-requests/77",
    "POST,/api/support-inquiries",
    "GET,/api/support-inquiries/me",
    "GET,/api/support-inquiries/5",
    "POST,/api/support-inquiries/5/replies",
    "POST,/api/support-inquiries/5/attachments",
    "GET,/api/support-inquiries/5/attachments/9"
  })
  void 표의_엔드포인트는_재동의가_필요해도_허용한다(String method, String path) {
    assertThat(ReconsentInterceptor.isExempt(method, path)).isTrue();
  }

  @ParameterizedTest
  @CsvSource({
    "GET,/api/me",
    "PATCH,/api/me",
    "GET,/api/me/login-history",
    "POST,/api/me/terms-agreements/1",
    "PUT,/api/me/terms-agreements",
    "GET,/api/properties",
    "POST,/api/auth/logout/all",
    "GET,/api/support-inquiries/5/replies",
    "DELETE,/api/support-inquiries/5"
  })
  void 표에_없는_요청은_제한_대상이다(String method, String path) {
    assertThat(ReconsentInterceptor.isExempt(method, path)).isFalse();
  }

  @Test
  void 재동의가_필요한_구성원_세션의_업무_요청은_403이다() {
    loginAsMember();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/properties");

    assertThatThrownBy(
            () -> interceptor(true).preHandle(request, new MockHttpServletResponse(), new Object()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RECONSENT_REQUIRED));
  }

  @Test
  void 재동의가_필요해도_면제_엔드포인트는_통과한다() {
    loginAsMember();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me/session");

    assertThat(interceptor(true).preHandle(request, new MockHttpServletResponse(), new Object()))
        .isTrue();
  }

  @Test
  void 재동의가_필요하지_않으면_통과한다() {
    loginAsMember();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/properties");

    assertThat(interceptor(false).preHandle(request, new MockHttpServletResponse(), new Object()))
        .isTrue();
  }

  @Test
  void 세션이_없는_요청은_제한하지_않는다() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/terms");

    assertThat(interceptor(true).preHandle(request, new MockHttpServletResponse(), new Object()))
        .isTrue();
  }
}
