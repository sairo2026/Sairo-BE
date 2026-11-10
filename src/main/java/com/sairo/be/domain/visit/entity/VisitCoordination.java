package com.sairo.be.domain.visit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "visit_coordination")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VisitCoordination {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "office_id", nullable = false, updatable = false)
  private Long officeId;

  @Column(name = "customer_id", nullable = false, updatable = false)
  private Long customerId;

  @Column(name = "manager_membership_id", nullable = false)
  private Long managerMembershipId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private CoordinationStatus status;

  @Column(name = "period_start_date", nullable = false)
  private LocalDate periodStartDate;

  @Column(name = "period_end_date", nullable = false)
  private LocalDate periodEndDate;

  @Column(name = "cancelled_at")
  private Instant cancelledAt;

  @Column(name = "cancel_reason", columnDefinition = "text")
  private String cancelReason;

  @Enumerated(EnumType.STRING)
  @Column(name = "reschedule_trigger_type", length = 20)
  private RescheduleTriggerType rescheduleTriggerType;

  @Column(name = "reschedule_reason", columnDefinition = "text")
  private String rescheduleReason;

  @Column(name = "reschedule_raised_at")
  private Instant rescheduleRaisedAt;

  @Column(name = "reschedule_approval_id")
  private Long rescheduleApprovalId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private Long version = 1L;

  public static VisitCoordination open(
      Long officeId,
      Long customerId,
      Long managerMembershipId,
      LocalDate periodStartDate,
      LocalDate periodEndDate) {
    VisitCoordination coordination = new VisitCoordination();
    coordination.officeId = officeId;
    coordination.customerId = customerId;
    coordination.managerMembershipId = managerMembershipId;
    coordination.periodStartDate = periodStartDate;
    coordination.periodEndDate = periodEndDate;
    coordination.status = CoordinationStatus.TIME_COLLECTING;
    return coordination;
  }

  public VisitCoordinationStatusHistory openingHistory(
      Long actorMembershipId, StatusChangeSource source, Instant at) {
    return VisitCoordinationStatusHistory.record(
        id, null, CoordinationStatus.TIME_COLLECTING, actorMembershipId, source, null, at);
  }

  public VisitCoordinationStatusHistory moveTo(
      CoordinationStatus next,
      StatusChangeSource source,
      Long actorMembershipId,
      String reason,
      Instant at) {
    if (next == CoordinationStatus.RESCHEDULE_REQUIRED) {
      throw new IllegalStateException("재조율 필요 전이는 경보 정보와 함께 전용 메서드로만 한다.");
    }
    if (!status.canMoveTo(next)) {
      throw new IllegalStateException("허용되지 않는 조율 상태 전이다: " + status + " -> " + next);
    }
    CoordinationStatus previous = status;
    status = next;
    if (previous == CoordinationStatus.RESCHEDULE_REQUIRED) {
      rescheduleTriggerType = null;
      rescheduleReason = null;
      rescheduleRaisedAt = null;
      rescheduleApprovalId = null;
    }
    if (next == CoordinationStatus.CANCELLED) {
      cancelledAt = at;
      cancelReason = reason;
    }
    touch(at);
    return VisitCoordinationStatusHistory.record(
        id, previous, next, actorMembershipId, source, reason, at);
  }

  public void touch(Instant at) {
    this.updatedAt = at;
  }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }
}
