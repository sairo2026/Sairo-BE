package com.sairo.be.global.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class SessionIssuer {

  private final Clock clock;

  public SessionIssuer(Clock clock) {
    this.clock = clock;
  }

  public HttpSession issue(HttpServletRequest request, SessionKind kind) {
    HttpSession previous = request.getSession(false);
    if (previous != null) {
      previous.invalidate();
    }
    HttpSession session = request.getSession(true);
    session.setMaxInactiveInterval((int) kind.idleTimeout().toSeconds());
    session.setAttribute(AbsoluteSessionTimeoutFilter.KIND_ATTRIBUTE, kind);
    session.setAttribute(AbsoluteSessionTimeoutFilter.ISSUED_AT_ATTRIBUTE, clock.instant());
    return session;
  }
}
