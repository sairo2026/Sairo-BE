package com.sairo.be.global.config;

import com.sairo.be.global.security.AbsoluteSessionTimeoutFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

// Excluded from the "migrate" profile: that profile runs with
// spring.main.web-application-type=none, so no HttpSecurity bean exists.
@Profile("!migrate")
@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .requestMatchers("/api/auth/kakao/start", "/api/auth/kakao/callback")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(new AbsoluteSessionTimeoutFilter(), AuthorizationFilter.class)
        .exceptionHandling(
            exceptionHandling ->
                exceptionHandling
                    .authenticationEntryPoint(
                        (request, response, authException) ->
                            response.sendError(HttpServletResponse.SC_UNAUTHORIZED))
                    .accessDeniedHandler(
                        (request, response, accessDeniedException) ->
                            response.sendError(HttpServletResponse.SC_FORBIDDEN)))
        .build();
  }
}
