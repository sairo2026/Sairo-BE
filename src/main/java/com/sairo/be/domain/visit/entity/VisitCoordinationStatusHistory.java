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
@Table(name = "visit_coordination_status_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VisitCoordinationStatusHistory {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "coordination_id", nullable = false, updatable = false)
  private Long coordinationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "from_status", length = 30, updatable = false)
  private CoordinationStatus fromStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "to_status", nullable = false, length = 30, updatable = false)
  private CoordinationStatus toStatus;

  @Column(name = "actor_membership_id", updatable = false)
  private Long actorMembershipId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10, updatable = false)
  private StatusChangeSource source;

  @Column(columnDefinition = "text", updatable = false)
  private String reason;

  @Column(name = "effective_at", nullable = false, updatable = false)
  private Instant effectiveAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  static VisitCoordinationStatusHistory record(
      Long coordinationId,
      CoordinationStatus from,
      CoordinationStatus to,
      Long actorMembershipId,
      StatusChangeSource source,
      String reason,
      Instant effectiveAt) {
    VisitCoordinationStatusHistory history = new VisitCoordinationStatusHistory();
    history.coordinationId = coordinationId;
    history.fromStatus = from;
    history.toStatus = to;
    history.actorMembershipId = actorMembershipId;
    history.source = source;
    history.reason = reason;
    history.effectiveAt = effectiveAt;
    return history;
  }

  @PrePersist
  void onCreate() {
    createdAt = Instant.now();
  }
}
