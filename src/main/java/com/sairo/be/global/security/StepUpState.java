package com.sairo.be.global.security;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class StepUpState {

  public static final String STEP_UP_UNTIL_ATTRIBUTE = "AUTH_STEP_UP_UNTIL";
  public static final Duration VALIDITY = Duration.ofMinutes(10);

  private final Clock clock;

  public StepUpState(Clock clock) {
    this.clock = clock;
  }

  public Instant grant(HttpSession session) {
    Instant until = clock.instant().plus(VALIDITY);
    session.setAttribute(STEP_UP_UNTIL_ATTRIBUTE, until);
    return until;
  }

  public Optional<Instant> validUntil(HttpSession session) {
    if (session == null) {
      return Optional.empty();
    }
    Object value = session.getAttribute(STEP_UP_UNTIL_ATTRIBUTE);
    if (value instanceof Instant until && until.isAfter(clock.instant())) {
      return Optional.of(until);
    }
    return Optional.empty();
  }

  public void require(HttpSession session) {
    if (validUntil(session).isEmpty()) {
      throw new BusinessException(ErrorCode.STEP_UP_REQUIRED);
    }
  }

  public void consume(HttpSession session) {
    if (session != null) {
      session.removeAttribute(STEP_UP_UNTIL_ATTRIBUTE);
    }
  }
}
