package com.sairo.be.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.session.web.http.DefaultCookieSerializer;

// server.servlet.session.cookie.* only configures the servlet container's own session
// cookie, not Spring Session's. Spring Session's SessionRepositoryFilter picks up a
// user-defined DefaultCookieSerializer bean instead of building its own default one.
@Profile("!migrate")
@Configuration
public class SessionCookieConfig {

  @Bean
  DefaultCookieSerializer cookieSerializer() {
    DefaultCookieSerializer serializer = new DefaultCookieSerializer();
    serializer.setCookieName("__Host-sairo_session");
    serializer.setCookiePath("/");
    serializer.setUseHttpOnlyCookie(true);
    serializer.setUseSecureCookie(true);
    serializer.setSameSite("Lax");
    return serializer;
  }
}
