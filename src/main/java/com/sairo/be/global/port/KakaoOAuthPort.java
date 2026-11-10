package com.sairo.be.global.port;

public interface KakaoOAuthPort {

  String buildAuthorizeUrl(String state);

  String exchangeToken(String code);

  Long fetchUserId(String accessToken);
}
