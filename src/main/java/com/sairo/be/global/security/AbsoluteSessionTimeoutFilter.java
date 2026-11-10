package com.sairo.be.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

  public static final String ISSUED_AT_ATTRIBUTE = "AUTH_SESSION_ISSUED_AT";
  public static final String KIND_ATTRIBUTE = "AUTH_SESSION_KIND";

  private final Clock clock;

  public AbsoluteSessionTimeoutFilter(Clock clock) {
    this.clock = clock;
  }

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
    if (!(issuedAt instanceof Instant issuedInstant)) {
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      return issuedAt != null
          || (authentication != null
              && authentication.isAuthenticated()
              && !(authentication instanceof AnonymousAuthenticationToken));
    }
    Instant now = clock.instant();
    SessionKind kind = kindOf(session);
    return issuedInstant.isAfter(now) || !issuedInstant.isAfter(now.minus(kind.absoluteTimeout()));
  }

  private SessionKind kindOf(HttpSession session) {
    Object kind = session.getAttribute(KIND_ATTRIBUTE);
    return kind instanceof SessionKind sessionKind ? sessionKind : SessionKind.MEMBER;
  }
}
