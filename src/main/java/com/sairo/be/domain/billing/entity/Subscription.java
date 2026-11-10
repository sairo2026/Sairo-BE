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

  public static Subscription startTrial(
      Long officeId, SubscriptionPlan plan, Instant startedAt, Instant endsAt, Long trialEventId) {
    Subscription subscription = new Subscription();
    subscription.officeId = officeId;
    subscription.plan = plan;
    subscription.applyTrial(startedAt, endsAt, trialEventId);
    return subscription;
  }

  public static Subscription startPrePayment(
      Long officeId, SubscriptionPlan plan, Instant prePaymentSince) {
    Subscription subscription = new Subscription();
    subscription.officeId = officeId;
    subscription.plan = plan;
    subscription.status = SubscriptionStatus.PRE_PAYMENT;
    subscription.prePaymentSince = prePaymentSince;
    return subscription;
  }

  public void applyTrial(Instant startedAt, Instant endsAt, Long trialEventId) {
    this.status = SubscriptionStatus.TRIAL;
    this.trialStartedAt = startedAt;
    this.trialEndsAt = endsAt;
    this.trialEventId = trialEventId;
    this.firstBillingScheduledAt = endsAt;
    this.prePaymentSince = null;
  }

  public void extendTrial(Instant endsAt, Long trialEventId) {
    this.trialEndsAt = endsAt;
    this.trialEventId = trialEventId;
    this.firstBillingScheduledAt = endsAt;
  }

  public void activate(Instant periodStart, Instant periodEnd, Instant anchor) {
    this.status = SubscriptionStatus.ACTIVE;
    this.currentPeriodStart = periodStart;
    this.currentPeriodEnd = periodEnd;
    if (anchor != null) {
      this.cycleAnchorAt = anchor;
    }
    this.pastDueSince = null;
    this.endedAt = null;
    this.prePaymentSince = null;
  }

  public void startPastDue(Instant since) {
    this.status = SubscriptionStatus.PAST_DUE_GRACE;
    this.pastDueSince = since;
  }

  public void restrictPastDue() {
    this.status = SubscriptionStatus.PAST_DUE_RESTRICTED;
  }

  public void end(Instant endedAt) {
    this.status = SubscriptionStatus.ENDED;
    this.endedAt = endedAt;
    this.pastDueSince = null;
  }

  public void enterPrePayment(Instant since) {
    this.status = SubscriptionStatus.PRE_PAYMENT;
    this.prePaymentSince = since;
  }

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
