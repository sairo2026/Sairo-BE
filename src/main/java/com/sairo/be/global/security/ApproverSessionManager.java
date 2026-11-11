package com.sairo.be.global.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/** 승인자 세션의 발급, 재인증 시각, 동시 세션 상한, 일괄 종료를 맡는다. */
@Profile("!migrate")
@Component
public class ApproverSessionManager {

  public static final int MAX_SESSIONS = 5;
  public static final Duration REAUTH_VALIDITY = Duration.ofMinutes(10);
  public static final String REAUTH_UNTIL_ATTRIBUTE = "APPROVER_REAUTH_UNTIL";

  private final SessionIssuer sessionIssuer;
  private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;
  private final Clock clock;
  private final SecurityContextRepository securityContextRepository =
      new HttpSessionSecurityContextRepository();

  public ApproverSessionManager(
      SessionIssuer sessionIssuer,
      FindByIndexNameSessionRepository<? extends Session> sessionRepository,
      Clock clock) {
    this.sessionIssuer = sessionIssuer;
    this.sessionRepository = sessionRepository;
    this.clock = clock;
  }

  /** 새 세션을 발급한다. 이미 세션이 상한만큼 있으면 오래된 세션부터 끝낸다. */
  public HttpSession begin(
      HttpServletRequest request, HttpServletResponse response, ApproverPrincipal principal) {
    trimOldest(principal.profileId(), MAX_SESSIONS - 1);
    HttpSession session = sessionIssuer.issue(request, SessionKind.APPROVER);
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(new ApproverAuthentication(principal));
    SecurityContextHolder.setContext(context);
    securityContextRepository.saveContext(context, request, response);
    Object csrfToken = request.getAttribute(CsrfToken.class.getName());
    if (csrfToken instanceof CsrfToken token) {
      token.getToken();
    }
    return session;
  }

  public Instant issuedAt(HttpSession session) {
    Object value = session.getAttribute(AbsoluteSessionTimeoutFilter.ISSUED_AT_ATTRIBUTE);
    return value instanceof Instant instant ? instant : clock.instant();
  }

  public Instant grantReauth(HttpSession session) {
    Instant until = clock.instant().plus(REAUTH_VALIDITY);
    session.setAttribute(REAUTH_UNTIL_ATTRIBUTE, until);
    return until;
  }

  public Optional<Instant> reauthUntil(HttpSession session) {
    Object value = session == null ? null : session.getAttribute(REAUTH_UNTIL_ATTRIBUTE);
    if (value instanceof Instant until && until.isAfter(clock.instant())) {
      return Optional.of(until);
    }
    return Optional.empty();
  }

  public void end(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    SecurityContextHolder.clearContext();
  }

  /** 프로필의 저장된 세션을 모두 끝내고 끝낸 수를 돌려준다. */
  public int terminateAll(Long profileId) {
    Map<String, ? extends Session> sessions =
        sessionRepository.findByPrincipalName(ApproverAuthentication.principalName(profileId));
    sessions.keySet().forEach(sessionRepository::deleteById);
    return sessions.size();
  }

  private void trimOldest(Long profileId, int keep) {
    Map<String, ? extends Session> sessions =
        sessionRepository.findByPrincipalName(ApproverAuthentication.principalName(profileId));
    if (sessions.size() <= keep) {
      return;
    }
    List<Session> ordered = new ArrayList<>(sessions.values());
    ordered.sort(Comparator.comparing(Session::getCreationTime));
    for (int i = 0; i < ordered.size() - keep; i++) {
      sessionRepository.deleteById(ordered.get(i).getId());
    }
  }
}
