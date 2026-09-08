package com.sairo.be.global.config;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.ErrorResponseWriter;
import com.sairo.be.global.security.AbsoluteSessionTimeoutFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

// Excluded from the "migrate" profile: that profile runs with
// spring.main.web-application-type=none, so no HttpSecurity bean exists.
@Profile("!migrate")
@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, ErrorResponseWriter errorResponseWriter) throws Exception {
    return http.authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .requestMatchers("/api/auth/kakao/start", "/api/auth/kakao/callback")
                    .permitAll()
                    .requestMatchers("/api/public/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        // Session-cookie auth needs CSRF protection on state-changing requests. The token is
        // exposed via a readable XSRF-TOKEN cookie so the frontend can echo it back as
        // X-XSRF-TOKEN, since there's no server-rendered page to embed it in. Public link APIs
        // authenticate with a URL token instead of a session cookie, so CSRF doesn't apply
        // there.
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .ignoringRequestMatchers("/api/public/**"))
        // Public link screens must not be cached and must not leak the link in a Referer header.
        .headers(
            headers ->
                headers.addHeaderWriter(
                    new DelegatingRequestMatcherHeaderWriter(
                        PathPatternRequestMatcher.pathPattern("/api/public/**"),
                        (request, response) -> {
                          response.setHeader("Cache-Control", "no-store");
                          response.setHeader("Referrer-Policy", "no-referrer");
                        })))
        .addFilterBefore(new AbsoluteSessionTimeoutFilter(), AuthorizationFilter.class)
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
