package com.sairo.be.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.HttpSessionIdResolver;

class SessionCookieConfigTest {

  private static final String PROD_NAME = "__Host-APPROVER_SESSION";
  private static final String PORTAL_PATH = "/api/approver-portal/session";

  private static String configuredName(String resource) throws IOException {
    return new YamlPropertySourceLoader()
        .load(resource, new ClassPathResource(resource)).stream()
            .map(source -> source.getProperty(SessionCookieConfig.APPROVER_COOKIE_NAME_PROPERTY))
            .filter(Objects::nonNull)
            .map(Object::toString)
            .findFirst()
            .orElse(null);
  }

  private static HttpSessionIdResolver resolver(String approverCookieName) {
    SessionCookieConfig config = new SessionCookieConfig();
    return config.httpSessionIdResolver(config.cookieSerializer(), approverCookieName);
  }

  private static String encoded(String sessionId) {
    return Base64.getEncoder().encodeToString(sessionId.getBytes(StandardCharsets.UTF_8));
  }

  private static List<String> attributes(String setCookie) {
    return Arrays.stream(setCookie.split(";")).map(String::strip).toList();
  }

  @Test
  void 기본_설정의_승인자_세션_쿠키_이름은_APPROVER_SESSION이다() throws IOException {
    assertThat(configuredName("application.yml"))
        .isEqualTo(SessionCookieConfig.DEFAULT_APPROVER_COOKIE_NAME)
        .isEqualTo("APPROVER_SESSION");
  }

  @Test
  void 운영_프로필의_승인자_세션_쿠키_이름은_Host_접두사를_쓴다() throws IOException {
    assertThat(configuredName("application-prod.yml")).isEqualTo(PROD_NAME);
  }

  @Test
  void 운영_이름으로_쓴_승인자_세션_쿠키는_Secure와_루트_경로를_갖고_Domain이_없다() throws IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", PORTAL_PATH);
    request.setServerName("app.example.com");
    MockHttpServletResponse response = new MockHttpServletResponse();

    resolver(configuredName("application-prod.yml")).setSessionId(request, response, "session-1");

    List<String> attributes = attributes(response.getHeader("Set-Cookie"));
    assertThat(attributes.get(0)).isEqualTo(PROD_NAME + "=" + encoded("session-1"));
    assertThat(attributes).contains("Path=/", "Secure", "HttpOnly", "SameSite=Lax");
    assertThat(attributes).noneMatch(attribute -> attribute.toLowerCase().startsWith("domain"));
    assertThat(attributes)
        .filteredOn(attribute -> attribute.startsWith("Path="))
        .containsExactly("Path=/");
  }

  @Test
  void 세션을_끝낼_때도_같은_이름과_속성으로_쿠키를_지운다() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", PORTAL_PATH);
    MockHttpServletResponse response = new MockHttpServletResponse();

    resolver(PROD_NAME).expireSession(request, response);

    List<String> attributes = attributes(response.getHeader("Set-Cookie"));
    assertThat(attributes.get(0)).isEqualTo(PROD_NAME + "=");
    assertThat(attributes).contains("Path=/", "Secure", "Max-Age=0");
    assertThat(attributes).noneMatch(attribute -> attribute.toLowerCase().startsWith("domain"));
  }

  @Test
  void 승인자_경로는_설정한_이름의_쿠키만_세션으로_읽는다() {
    HttpSessionIdResolver resolver = resolver(PROD_NAME);
    MockHttpServletRequest configured = new MockHttpServletRequest("GET", PORTAL_PATH);
    configured.setCookies(new Cookie(PROD_NAME, encoded("session-1")));
    MockHttpServletRequest legacy = new MockHttpServletRequest("GET", PORTAL_PATH);
    legacy.setCookies(
        new Cookie(SessionCookieConfig.DEFAULT_APPROVER_COOKIE_NAME, encoded("session-2")),
        new Cookie(SessionCookieConfig.MEMBER_COOKIE_NAME, encoded("session-3")));

    assertThat(resolver.resolveSessionIds(configured)).containsExactly("session-1");
    assertThat(resolver.resolveSessionIds(legacy)).isEmpty();
  }

  @Test
  void 기본_이름으로도_같은_쿠키_속성을_쓴다() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", PORTAL_PATH);
    MockHttpServletResponse response = new MockHttpServletResponse();

    resolver(SessionCookieConfig.DEFAULT_APPROVER_COOKIE_NAME)
        .setSessionId(request, response, "session-1");

    List<String> attributes = attributes(response.getHeader("Set-Cookie"));
    assertThat(attributes.get(0)).isEqualTo("APPROVER_SESSION=" + encoded("session-1"));
    assertThat(attributes).contains("Path=/", "Secure", "HttpOnly", "SameSite=Lax");
  }

  @Test
  void 쿠키_이름이_비어_있으면_설정을_만들지_않는다() {
    assertThatThrownBy(() -> resolver(" ")).isInstanceOf(IllegalArgumentException.class);
  }
}
