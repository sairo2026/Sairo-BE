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
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Profile("!migrate")
@Configuration
public class SecurityConfig {

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
            headers ->
                headers.addHeaderWriter(
                    new DelegatingRequestMatcherHeaderWriter(
                        PathPatternRequestMatcher.pathPattern("/api/public/**"),
                        (request, response) -> {
                          if (!response.containsHeader("Cache-Control")) {
                            response.setHeader("Cache-Control", "no-store");
                          }
                          response.setHeader("Referrer-Policy", "no-referrer");
                        })))
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
