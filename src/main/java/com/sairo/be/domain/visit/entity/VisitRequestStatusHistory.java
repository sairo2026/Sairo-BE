package com.sairo.be.domain.visit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "visit_request_status_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VisitRequestStatusHistory {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "request_id", nullable = false, updatable = false)
  private Long requestId;

  @Enumerated(EnumType.STRING)
  @Column(name = "from_status", length = 24, updatable = false)
  private RequestStatus fromStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "to_status", nullable = false, length = 24, updatable = false)
  private RequestStatus toStatus;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10, updatable = false)
  private RequestStatusSource source;

  @Column(name = "actor_membership_id", updatable = false)
  private Long actorMembershipId;

  @Column(columnDefinition = "text", updatable = false)
  private String reason;

  @Column(name = "effective_at", nullable = false, updatable = false)
  private Instant effectiveAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  static VisitRequestStatusHistory record(
      Long requestId,
      RequestStatus from,
      RequestStatus to,
      RequestStatusSource source,
      Long actorMembershipId,
      String reason,
      Instant effectiveAt) {
    VisitRequestStatusHistory history = new VisitRequestStatusHistory();
    history.requestId = requestId;
    history.fromStatus = from;
    history.toStatus = to;
    history.source = source;
    history.actorMembershipId = source == RequestStatusSource.BROKER ? actorMembershipId : null;
    history.reason = reason;
    history.effectiveAt = effectiveAt;
    return history;
  }

  @PrePersist
  void onCreate() {
    createdAt = Instant.now();
  }
}
