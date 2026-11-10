package com.sairo.be.global.security;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
@SuppressWarnings({"rawtypes", "unchecked"})
public class MemberSessionRefresher {

  private final FindByIndexNameSessionRepository sessionRepository;

  public MemberSessionRefresher(FindByIndexNameSessionRepository<?> sessionRepository) {
    this.sessionRepository = sessionRepository;
  }

  public int replacePrincipal(Long userId, StaffPrincipal principal) {
    Map<String, Session> sessions = sessionRepository.findByPrincipalName(String.valueOf(userId));
    int refreshed = 0;
    for (String sessionId : sessions.keySet()) {
      Session stored = (Session) sessionRepository.findById(sessionId);
      if (stored == null) {
        continue;
      }
      SecurityContext context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(new StaffAuthentication(principal));
      stored.setAttribute(
          HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
      sessionRepository.save(stored);
      refreshed++;
    }
    return refreshed;
  }
}
