package com.sairo.be.domain.auth.service;

import com.sairo.be.global.port.KakaoOAuthPort;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Profile("!migrate")
@Service
public class KakaoAuthorizationService {

  public static final String FLOW_ATTRIBUTE = "KAKAO_AUTH_FLOW";

  private final KakaoOAuthPort kakaoOAuthPort;
  private final OAuthStateService oauthStateService;

  public KakaoAuthorizationService(
      KakaoOAuthPort kakaoOAuthPort, OAuthStateService oauthStateService) {
    this.kakaoOAuthPort = kakaoOAuthPort;
    this.oauthStateService = oauthStateService;
  }

  public String start(HttpSession session, KakaoAuthFlow flow) {
    session.setAttribute(FLOW_ATTRIBUTE, flow.name());
    String state = oauthStateService.issue(session.getId());
    return kakaoOAuthPort.buildAuthorizeUrl(state, flow.portFlow());
  }

  public Optional<String> resolveProviderKey(
      HttpSession session, KakaoAuthFlow expected, String code, String state, String error) {
    if (session == null) {
      return Optional.empty();
    }
    boolean validState = oauthStateService.consume(session.getId(), state);
    Object storedFlow = session.getAttribute(FLOW_ATTRIBUTE);
    session.removeAttribute(FLOW_ATTRIBUTE);
    boolean validFlow = expected.name().equals(storedFlow);
    if (error != null || code == null || code.isBlank() || !validState || !validFlow) {
      log.warn(
          "카카오 인증 콜백이 인가 단계에서 실패했습니다. flow={}, error={}, validState={}, validFlow={}",
          expected,
          error,
          validState,
          validFlow);
      return Optional.empty();
    }
    try {
      String accessToken = kakaoOAuthPort.exchangeToken(code, expected.portFlow());
      return Optional.of(String.valueOf(kakaoOAuthPort.fetchUserId(accessToken)));
    } catch (RestClientResponseException e) {
      log.warn("카카오 인증 요청이 실패했습니다. status={}", e.getStatusCode());
      return Optional.empty();
    } catch (RestClientException e) {
      log.warn("카카오 인증 요청이 실패했습니다.");
      return Optional.empty();
    }
  }
}
