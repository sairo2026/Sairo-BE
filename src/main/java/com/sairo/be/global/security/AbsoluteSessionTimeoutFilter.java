package com.sairo.be.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

// Spring Session은 미활동 만료만 처리하므로 발급 후 90일의 절대 만료는 별도로 검사한다.
// 보안 필터 체인 안에서 한 번만 실행하도록 SecurityConfig에서 직접 등록한다.
public class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

  public static final String ISSUED_AT_ATTRIBUTE = "AUTH_SESSION_ISSUED_AT";

  private static final Duration ABSOLUTE_TIMEOUT = Duration.ofDays(90);

  private final Clock clock;

  public AbsoluteSessionTimeoutFilter() {
    this(Clock.systemUTC());
  }

  AbsoluteSessionTimeoutFilter(Clock clock) {
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
    return issuedInstant.isAfter(now) || !issuedInstant.isAfter(now.minus(ABSOLUTE_TIMEOUT));
  }
}
