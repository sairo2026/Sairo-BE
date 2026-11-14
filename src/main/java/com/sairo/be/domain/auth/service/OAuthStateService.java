package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.repository.OAuthStateRepository;
import com.sairo.be.global.support.TokenGenerator;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Profile("!migrate")
@Service
@RequiredArgsConstructor
public class OAuthStateService {

  public static final Duration STATE_TIME_TO_LIVE = Duration.ofMinutes(10);

  private final OAuthStateRepository repository;
  private final TokenGenerator tokenGenerator;

  public String issue(String sessionId) {
    String state = tokenGenerator.randomToken();
    repository.save(state, sessionId, STATE_TIME_TO_LIVE);
    return state;
  }

  public boolean consume(String sessionId, String actualState) {
    if (sessionId == null || actualState == null || actualState.isBlank()) {
      return false;
    }
    return repository.consume(actualState).map(sessionId::equals).orElse(false);
  }
}
