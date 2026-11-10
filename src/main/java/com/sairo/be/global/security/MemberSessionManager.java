package com.sairo.be.global.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class MemberSessionManager {

  private final SessionIssuer sessionIssuer;
  private final SecurityContextRepository securityContextRepository =
      new HttpSessionSecurityContextRepository();

  public MemberSessionManager(SessionIssuer sessionIssuer) {
    this.sessionIssuer = sessionIssuer;
  }

  public HttpSession begin(
      HttpServletRequest request, HttpServletResponse response, StaffPrincipal principal) {
    HttpSession session = sessionIssuer.issue(request, SessionKind.MEMBER);
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(new StaffAuthentication(principal));
    SecurityContextHolder.setContext(context);
    securityContextRepository.saveContext(context, request, response);
    Object csrfToken = request.getAttribute(CsrfToken.class.getName());
    if (csrfToken instanceof CsrfToken token) {
      token.getToken();
    }
    return session;
  }

  public void end(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    SecurityContextHolder.clearContext();
  }
}
