package com.sairo.be.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

// Idle timeout is handled by server.servlet.session.timeout (Spring Session evicts the
// row itself). Absolute timeout has no built-in Spring Session equivalent, so this filter
// invalidates the session once it has existed for longer than ABSOLUTE_TIMEOUT regardless
// of activity. Registered explicitly in SecurityConfig, not as a @Component, so it only
// ever runs once and at the exact position needed inside the security filter chain.
public class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

  public static final String ISSUED_AT_ATTRIBUTE = "AUTH_SESSION_ISSUED_AT";

  private static final Duration ABSOLUTE_TIMEOUT = Duration.ofDays(90);

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    HttpSession session = request.getSession(false);
    if (session != null && isExpired(session)) {
      session.invalidate();
      SecurityContextHolder.clearContext();
    }
    filterChain.doFilter(request, response);
  }

  private boolean isExpired(HttpSession session) {
    Object issuedAt = session.getAttribute(ISSUED_AT_ATTRIBUTE);
    return issuedAt instanceof Instant issuedInstant
        && Instant.now().isAfter(issuedInstant.plus(ABSOLUTE_TIMEOUT));
  }
}
