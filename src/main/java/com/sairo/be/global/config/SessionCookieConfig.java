package com.sairo.be.global.config;

import com.sairo.be.global.security.PathAwareSessionIdResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.HttpSessionIdResolver;

@Profile("!migrate")
@Configuration
public class SessionCookieConfig {

  public static final String MEMBER_COOKIE_NAME = "__Host-sairo_session";
  public static final String OPERATOR_COOKIE_NAME = "__Host-sairo_ops_session";
  public static final String APPROVER_COOKIE_NAME_PROPERTY = "sairo.session.approver-cookie-name";
  public static final String DEFAULT_APPROVER_COOKIE_NAME = "APPROVER_SESSION";

  @Bean
  DefaultCookieSerializer cookieSerializer() {
    return serializer(MEMBER_COOKIE_NAME);
  }

  @Bean
  HttpSessionIdResolver httpSessionIdResolver(
      DefaultCookieSerializer cookieSerializer,
      @Value("${" + APPROVER_COOKIE_NAME_PROPERTY + ":" + DEFAULT_APPROVER_COOKIE_NAME + "}")
          String approverCookieName) {
    return new PathAwareSessionIdResolver(
        cookieSerializer, serializer(OPERATOR_COOKIE_NAME), serializer(approverCookieName));
  }

  static DefaultCookieSerializer serializer(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("세션 쿠키 이름이 비어 있습니다.");
    }
    DefaultCookieSerializer serializer = new DefaultCookieSerializer();
    serializer.setCookieName(name);
    serializer.setCookiePath("/");
    serializer.setUseHttpOnlyCookie(true);
    serializer.setUseSecureCookie(true);
    serializer.setSameSite("Lax");
    return serializer;
  }
}
