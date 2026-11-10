package com.sairo.be.support;

import com.sairo.be.global.security.AbsoluteSessionTimeoutFilter;
import com.sairo.be.global.security.SessionKind;
import com.sairo.be.global.security.StaffAuthentication;
import com.sairo.be.global.security.StaffPrincipal;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

@SuppressWarnings({"rawtypes", "unchecked"})
public class SessionTestSupport {

  public static final String SESSION_COOKIE_NAME = "__Host-sairo_session";

  private final SessionRepository sessionRepository;

  public SessionTestSupport(SessionRepository sessionRepository) {
    this.sessionRepository = sessionRepository;
  }

  public String createMemberSession(
      StaffPrincipal principal, Instant issuedAt, Instant lastAccess) {
    return createSession(principal, SessionKind.MEMBER, issuedAt, lastAccess);
  }

  public String createSession(
      StaffPrincipal principal, SessionKind kind, Instant issuedAt, Instant lastAccess) {
    Session session = sessionRepository.createSession();
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(new StaffAuthentication(principal));
    session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    session.setAttribute(AbsoluteSessionTimeoutFilter.KIND_ATTRIBUTE, kind);
    if (issuedAt != null) {
      session.setAttribute(AbsoluteSessionTimeoutFilter.ISSUED_AT_ATTRIBUTE, issuedAt);
    }
    session.setMaxInactiveInterval(kind.idleTimeout());
    session.setLastAccessedTime(lastAccess);
    sessionRepository.save(session);
    return session.getId();
  }

  public boolean exists(String sessionId) {
    return sessionRepository.findById(sessionId) != null;
  }

  public Cookie cookie(String sessionId) {
    return new Cookie(
        SESSION_COOKIE_NAME,
        Base64.getEncoder().encodeToString(sessionId.getBytes(StandardCharsets.UTF_8)));
  }
}
