package com.sairo.be.domain.auth.service;

import com.sairo.be.global.security.StaffPrincipal;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Profile("!migrate")
@Service
@RequiredArgsConstructor
public class AuthService {

  private final KakaoOAuthClient kakaoOAuthClient;
  private final StaffPrincipalQueryService staffPrincipalQueryService;

  public String buildAuthorizeUrl(String state) {
    return kakaoOAuthClient.buildAuthorizeUrl(state);
  }

  public Optional<StaffPrincipal> authenticate(String code) {
    Long kakaoUserId;
    try {
      String accessToken = kakaoOAuthClient.exchangeToken(code);
      kakaoUserId = kakaoOAuthClient.fetchUserId(accessToken);
    } catch (RestClientResponseException e) {
      log.warn("카카오 인증 요청이 실패했습니다. status={}", e.getStatusCode());
      return Optional.empty();
    } catch (RestClientException e) {
      log.warn("카카오 인증 요청이 실패했습니다.");
      return Optional.empty();
    }

    Optional<StaffPrincipal> principal = staffPrincipalQueryService.findByKakaoUserId(kakaoUserId);
    if (principal.isEmpty()) {
      log.warn("카카오 사용자 id={}에 해당하는 사무소 직원이 없습니다.", kakaoUserId);
    }
    return principal;
  }
}
