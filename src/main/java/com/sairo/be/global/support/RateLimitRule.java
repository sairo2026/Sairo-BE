package com.sairo.be.global.support;

import java.time.Duration;

public record RateLimitRule(String name, int limit, Duration window) {

  public static RateLimitRule of(String name, int limit, Duration window) {
    return new RateLimitRule(name, limit, window);
  }
}
