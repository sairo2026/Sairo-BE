package com.sairo.be.global.port;

public interface KakaoOAuthPort {

  String buildAuthorizeUrl(String state);

  String exchangeToken(String code);

  Long fetchUserId(String accessToken);

  default String buildAuthorizeUrl(String state, KakaoFlow flow) {
    return buildAuthorizeUrl(state);
  }

  default String exchangeToken(String code, KakaoFlow flow) {
    return exchangeToken(code);
  }
}
