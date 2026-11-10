package com.sairo.be.domain.billing.entity;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "subscription")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Subscription {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "office_id", nullable = false, updatable = false)
  private Long officeId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 12)
  private SubscriptionPlan plan;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private SubscriptionStatus status;

  @Column(name = "trial_started_at")
  private Instant trialStartedAt;

  @Column(name = "trial_ends_at")
  private Instant trialEndsAt;

  @Column(name = "trial_event_id")
  private Long trialEventId;

  @Column(name = "current_period_start")
  private Instant currentPeriodStart;

  @Column(name = "current_period_end")
  private Instant currentPeriodEnd;

  @Column(name = "cycle_anchor_at")
  private Instant cycleAnchorAt;

  @Column(name = "past_due_since")
  private Instant pastDueSince;

  @Column(name = "suspended_at")
  private Instant suspendedAt;

  @Column(name = "trial_remaining_seconds")
  private Long trialRemainingSeconds;

  @Column(name = "ended_at")
  private Instant endedAt;

  @Column(name = "first_billing_scheduled_at")
  private Instant firstBillingScheduledAt;

  @Column(name = "pre_payment_since")
  private Instant prePaymentSince;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private Long version = 1L;

  public void markEventRecorded(Instant now) {
    this.updatedAt = now;
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
