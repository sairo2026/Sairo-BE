package com.sairo.be.global.config;

import com.sairo.be.global.security.PathAwareSessionIdResolver;
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

  @Bean
  DefaultCookieSerializer cookieSerializer() {
    return serializer(MEMBER_COOKIE_NAME);
  }

  @Bean
  HttpSessionIdResolver httpSessionIdResolver(DefaultCookieSerializer cookieSerializer) {
    return new PathAwareSessionIdResolver(cookieSerializer, serializer(OPERATOR_COOKIE_NAME));
  }

  private static DefaultCookieSerializer serializer(String name) {
    DefaultCookieSerializer serializer = new DefaultCookieSerializer();
    serializer.setCookieName(name);
    serializer.setCookiePath("/");
    serializer.setUseHttpOnlyCookie(true);
    serializer.setUseSecureCookie(true);
    serializer.setSameSite("Lax");
    return serializer;
  }
}
