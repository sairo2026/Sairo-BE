package com.sairo.be.global.config;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.ErrorResponseWriter;
import com.sairo.be.global.security.AbsoluteSessionTimeoutFilter;
import com.sairo.be.global.security.CsrfCookieFilter;
import com.sairo.be.global.security.ExternalWebhookAuthenticationFilter;
import com.sairo.be.global.security.GuestLinkAuthenticationFilter;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.HstsHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;

@Profile("!migrate")
@Configuration
public class SecurityConfig {

  static final long HSTS_MAX_AGE_SECONDS = 63_072_000L;

  // TLS는 Cloudflare와 nginx에서 끝나 애플리케이션은 평문 요청으로 보므로, 보안 연결 여부와 무관하게 항상 내려준다.
  static HstsHeaderWriter hstsHeaderWriter() {
    HstsHeaderWriter writer = new HstsHeaderWriter();
    writer.setRequestMatcher(AnyRequestMatcher.INSTANCE);
    writer.setMaxAgeInSeconds(HSTS_MAX_AGE_SECONDS);
    writer.setIncludeSubDomains(true);
    writer.setPreload(false);
    return writer;
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      ErrorResponseWriter errorResponseWriter,
      Clock clock,
      @Value("${kakao.admin-key:}") String kakaoAdminKey)
      throws Exception {
    return http.authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .requestMatchers("/api/**")
                    .permitAll()
                    .requestMatchers(
                        "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs", "/v3/api-docs/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                    .ignoringRequestMatchers("/api/public/**", "/api/webhooks/**"))
        .headers(
            headers -> {
              headers.httpStrictTransportSecurity(hsts -> hsts.disable());
              headers.addHeaderWriter(hstsHeaderWriter());
              headers.addHeaderWriter(
                  new DelegatingRequestMatcherHeaderWriter(
                      PathPatternRequestMatcher.pathPattern("/api/public/**"),
                      (request, response) -> {
                        if (!response.containsHeader("Cache-Control")) {
                          response.setHeader("Cache-Control", "no-store");
                        }
                        response.setHeader("Referrer-Policy", "no-referrer");
                      }));
            })
        .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
        .addFilterBefore(new AbsoluteSessionTimeoutFilter(clock), AuthorizationFilter.class)
        .addFilterBefore(
            new ExternalWebhookAuthenticationFilter(() -> kakaoAdminKey), AuthorizationFilter.class)
        .addFilterBefore(new GuestLinkAuthenticationFilter(), AuthorizationFilter.class)
        .exceptionHandling(
            exceptionHandling ->
                exceptionHandling
                    .authenticationEntryPoint(
                        (request, response, authException) ->
                            errorResponseWriter.write(response, ErrorCode.UNAUTHENTICATED))
                    .accessDeniedHandler(
                        (request, response, accessDeniedException) ->
                            errorResponseWriter.write(response, ErrorCode.FORBIDDEN)))
        .build();
  }
}
