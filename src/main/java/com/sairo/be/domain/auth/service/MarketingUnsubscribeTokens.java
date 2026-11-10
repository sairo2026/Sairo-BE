package com.sairo.be.domain.auth.service;

import com.sairo.be.global.security.SecurityProperties;
import com.sairo.be.global.support.HmacSupport;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class MarketingUnsubscribeTokens {

  private static final String PREFIX = "unsubscribe|";

  private final SecurityProperties properties;

  public MarketingUnsubscribeTokens(SecurityProperties properties) {
    this.properties = properties;
  }

  public String issue(Long userId) {
    return userId + "." + sign(userId);
  }

  public Optional<Long> verify(String token) {
    if (token == null) {
      return Optional.empty();
    }
    int dot = token.indexOf('.');
    if (dot <= 0 || dot == token.length() - 1) {
      return Optional.empty();
    }
    try {
      long userId = Long.parseLong(token.substring(0, dot));
      String signature = token.substring(dot + 1);
      return HmacSupport.constantTimeEquals(sign(userId), signature)
          ? Optional.of(userId)
          : Optional.empty();
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  private String sign(long userId) {
    return HmacSupport.hmacBase64Url(properties.unsubscribeSecret(), PREFIX + userId);
  }
}
