package com.sairo.be.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SecurityHeadersTest {

  private static final String EXPECTED = "max-age=63072000 ; includeSubDomains";

  @Test
  void 평문_요청으로_보이는_프록시_뒤에서도_HSTS를_내려준다() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
    request.setSecure(false);
    MockHttpServletResponse response = new MockHttpServletResponse();

    SecurityConfig.hstsHeaderWriter().writeHeaders(request, response);

    assertThat(response.getHeader("Strict-Transport-Security")).isEqualTo(EXPECTED);
  }

  @Test
  void HSTS는_하위_도메인을_포함하고_preload를_쓰지_않는다() {
    MockHttpServletResponse response = new MockHttpServletResponse();

    SecurityConfig.hstsHeaderWriter()
        .writeHeaders(new MockHttpServletRequest("GET", "/api/me"), response);

    String value = response.getHeader("Strict-Transport-Security");
    assertThat(value).contains("max-age=63072000").contains("includeSubDomains");
    assertThat(value).doesNotContain("preload");
  }
}
