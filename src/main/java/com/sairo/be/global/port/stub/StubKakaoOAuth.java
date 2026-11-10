package com.sairo.be.global.port.stub;

import com.sairo.be.global.port.KakaoOAuthPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!prod & !migrate")
@Component
@ConditionalOnProperty(name = "sairo.integrations.kakao-oauth", havingValue = "stub")
public class StubKakaoOAuth implements KakaoOAuthPort {

  private static final String TOKEN_PREFIX = "stub-token-";

  @Override
  public String buildAuthorizeUrl(String state) {
    return "https://kauth.stub.invalid/oauth/authorize?state=" + state;
  }

  @Override
  public String exchangeToken(String code) {
    return TOKEN_PREFIX + code;
  }

  @Override
  public Long fetchUserId(String accessToken) {
    return Long.parseLong(accessToken.substring(TOKEN_PREFIX.length()));
  }
}
