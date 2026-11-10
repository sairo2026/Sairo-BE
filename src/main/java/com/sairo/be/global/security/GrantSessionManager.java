package com.sairo.be.global.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class GrantSessionManager {

  private final SessionIssuer sessionIssuer;
  private final SecurityContextRepository securityContextRepository =
      new HttpSessionSecurityContextRepository();

  public GrantSessionManager(SessionIssuer sessionIssuer) {
    this.sessionIssuer = sessionIssuer;
  }

  public HttpSession begin(
      HttpServletRequest request, HttpServletResponse response, GrantPrincipal principal) {
    HttpSession session = sessionIssuer.issue(request, SessionKind.GRANT);
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(new GrantAuthentication(principal));
    SecurityContextHolder.setContext(context);
    securityContextRepository.saveContext(context, request, response);
    return session;
  }
}
