package com.sairo.be.domain.coordination.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "coordination_status_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CoordinationStatusHistory {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "coordination_id", nullable = false)
  private Long coordinationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "from_status", length = 40)
  private CoordinationStatus fromStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "to_status", nullable = false, length = 40)
  private CoordinationStatus toStatus;

  @Column(name = "actor_membership_id")
  private Long actorMembershipId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private CoordinationStatusSource source;

  @Column(columnDefinition = "TEXT")
  private String reason;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  private CoordinationStatusHistory(
      Long coordinationId,
      CoordinationStatus fromStatus,
      CoordinationStatus toStatus,
      Long actorMembershipId,
      CoordinationStatusSource source,
      String reason) {
    this.coordinationId = coordinationId;
    this.fromStatus = fromStatus;
    this.toStatus = toStatus;
    this.actorMembershipId = actorMembershipId;
    this.source = source;
    this.reason = reason;
  }

  public static CoordinationStatusHistory initialTransition(
      Long coordinationId, CoordinationStatus toStatus, Long actorMembershipId) {
    return new CoordinationStatusHistory(
        coordinationId, null, toStatus, actorMembershipId, CoordinationStatusSource.STAFF, null);
  }
}
