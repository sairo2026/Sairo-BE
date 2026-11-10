package com.sairo.be.domain.auth.service;

import com.sairo.be.global.port.KakaoFlow;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kakao")
public record KakaoProperties(
    String restApiKey,
    String clientSecret,
    String redirectUri,
    String stepUpRedirectUri,
    String linkRedirectUri,
    String operatorRedirectUri,
    String adminKey,
    String appId) {

  public String redirectUriFor(KakaoFlow flow) {
    String specific =
        switch (flow) {
          case LOGIN -> redirectUri;
          case LINK -> linkRedirectUri;
          case STEP_UP -> stepUpRedirectUri;
          case OPERATOR -> operatorRedirectUri;
        };
    return specific == null || specific.isBlank() ? redirectUri : specific;
  }
}
