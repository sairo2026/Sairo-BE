package com.sairo.be.domain.ops.service;

import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class OperatorSessionState {

  static final String MFA_OPERATOR = "OPS_MFA_PENDING_OPERATOR";
  static final String MFA_UNTIL = "OPS_MFA_PENDING_UNTIL";
  static final String ENROLL_INVITATION = "OPS_ENROLL_INVITATION";
  static final String ENROLL_UNTIL = "OPS_ENROLL_UNTIL";
  static final String START_INVITATION = "OPS_START_INVITATION";
  public static final Duration MFA_PENDING_VALIDITY = Duration.ofMinutes(10);
  public static final Duration ENROLLMENT_VALIDITY = Duration.ofMinutes(15);

  private final Clock clock;

  public OperatorSessionState(Clock clock) {
    this.clock = clock;
  }

  public void markMfaPending(HttpSession session, long operatorId) {
    session.setAttribute(MFA_OPERATOR, operatorId);
    session.setAttribute(MFA_UNTIL, clock.instant().plus(MFA_PENDING_VALIDITY));
  }

  public Optional<Long> mfaPendingOperator(HttpSession session) {
    return valid(session, MFA_OPERATOR, MFA_UNTIL);
  }

  public void markStartInvitation(HttpSession session, long invitationId) {
    session.setAttribute(START_INVITATION, invitationId);
  }

  public Optional<Long> startInvitation(HttpSession session) {
    Object value = session == null ? null : session.getAttribute(START_INVITATION);
    return value instanceof Long id ? Optional.of(id) : Optional.empty();
  }

  public Instant markEnrollment(HttpSession session, long invitationId) {
    Instant until = clock.instant().plus(ENROLLMENT_VALIDITY);
    session.setAttribute(ENROLL_INVITATION, invitationId);
    session.setAttribute(ENROLL_UNTIL, until);
    return until;
  }

  public Optional<Long> enrollmentInvitation(HttpSession session) {
    return valid(session, ENROLL_INVITATION, ENROLL_UNTIL);
  }

  private Optional<Long> valid(HttpSession session, String idKey, String untilKey) {
    if (session == null) {
      return Optional.empty();
    }
    Object id = session.getAttribute(idKey);
    Object until = session.getAttribute(untilKey);
    if (id instanceof Long value
        && until instanceof Instant limit
        && limit.isAfter(clock.instant())) {
      return Optional.of(value);
    }
    return Optional.empty();
  }
}
