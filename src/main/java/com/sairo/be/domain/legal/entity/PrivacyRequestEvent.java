package com.sairo.be.domain.legal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "privacy_request_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PrivacyRequestEvent {

  public static final String RECEIVED = "RECEIVED";
  public static final String REQUEST_SUPPLEMENT = "REQUEST_SUPPLEMENT";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "privacy_request_id", nullable = false, updatable = false)
  private Long privacyRequestId;

  @Column(name = "event_type", nullable = false, length = 30, updatable = false)
  private String eventType;

  @Enumerated(EnumType.STRING)
  @Column(name = "previous_status", length = 20, updatable = false)
  private PrivacyRequestStatus previousStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "new_status", nullable = false, length = 20, updatable = false)
  private PrivacyRequestStatus newStatus;

  @Column(name = "action_detail", nullable = false, columnDefinition = "text", updatable = false)
  private String actionDetail;

  @Column(name = "actor_operator_id", updatable = false)
  private Long actorOperatorId;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  public static PrivacyRequestEvent receivedFromGuestIntake(
      Long privacyRequestId, Long intakeId, Long operatorId, Instant now) {
    PrivacyRequestEvent event = received(privacyRequestId, now);
    event.actionDetail = "운영자가 비회원 접수(" + intakeId + ")의 본인 확인을 마치고 권리 요청으로 바꿨다.";
    event.actorOperatorId = operatorId;
    return event;
  }

  public static PrivacyRequestEvent received(Long privacyRequestId, Instant now) {
    PrivacyRequestEvent event = new PrivacyRequestEvent();
    event.privacyRequestId = privacyRequestId;
    event.eventType = RECEIVED;
    event.newStatus = PrivacyRequestStatus.RECEIVED;
    event.actionDetail = "회원이 요청을 접수했다.";
    event.occurredAt = now.truncatedTo(ChronoUnit.MICROS);
    return event;
  }

  public static PrivacyRequestEvent processed(
      Long privacyRequestId,
      String eventType,
      PrivacyRequestStatus previousStatus,
      PrivacyRequestStatus newStatus,
      String actionDetail,
      Long operatorId,
      Instant now) {
    PrivacyRequestEvent event = new PrivacyRequestEvent();
    event.privacyRequestId = privacyRequestId;
    event.eventType = eventType;
    event.previousStatus = previousStatus;
    event.newStatus = newStatus;
    event.actionDetail = actionDetail;
    event.actorOperatorId = operatorId;
    event.occurredAt = now.truncatedTo(ChronoUnit.MICROS);
    return event;
  }
}
