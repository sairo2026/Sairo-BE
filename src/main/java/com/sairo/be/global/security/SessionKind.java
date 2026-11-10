package com.sairo.be.global.security;

import java.time.Duration;

public enum SessionKind {
  MEMBER(Duration.ofHours(12), Duration.ofDays(7)),
  APPROVER(Duration.ofMinutes(60), Duration.ofHours(12)),
  OPERATOR(Duration.ofMinutes(30), Duration.ofHours(8)),
  GRANT(Duration.ofMinutes(10), Duration.ofMinutes(30));

  private final Duration idleTimeout;
  private final Duration absoluteTimeout;

  SessionKind(Duration idleTimeout, Duration absoluteTimeout) {
    this.idleTimeout = idleTimeout;
    this.absoluteTimeout = absoluteTimeout;
  }

  public Duration idleTimeout() {
    return idleTimeout;
  }

  public Duration absoluteTimeout() {
    return absoluteTimeout;
  }
}
