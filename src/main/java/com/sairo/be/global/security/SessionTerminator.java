package com.sairo.be.global.security;

import org.springframework.context.annotation.Profile;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class SessionTerminator {

  private final FindByIndexNameSessionRepository<?> sessionRepository;

  public SessionTerminator(FindByIndexNameSessionRepository<?> sessionRepository) {
    this.sessionRepository = sessionRepository;
  }

  public int terminateAll(Long userId) {
    var sessions = sessionRepository.findByPrincipalName(String.valueOf(userId));
    sessions.keySet().forEach(sessionRepository::deleteById);
    return sessions.size();
  }
}
