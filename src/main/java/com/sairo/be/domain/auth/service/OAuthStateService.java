package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.repository.OAuthStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.SerializationUtils;

@Profile("!migrate")
@Service
@RequiredArgsConstructor
public class OAuthStateService {

  public static final String STATE_SESSION_ATTRIBUTE = "KAKAO_OAUTH_STATE";
  private final OAuthStateRepository repository;

  @Transactional
  public boolean consume(String sessionId, String expectedState, String actualState) {
    boolean consumed =
        repository.deleteIfPresent(
            sessionId,
            STATE_SESSION_ATTRIBUTE,
            SerializationUtils.serialize(expectedState),
            System.currentTimeMillis());
    return consumed && expectedState.equals(actualState);
  }
}
