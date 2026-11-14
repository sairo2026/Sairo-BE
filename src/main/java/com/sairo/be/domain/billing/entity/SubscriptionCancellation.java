package com.sairo.be.domain.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "subscription_cancellation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubscriptionCancellation {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "subscription_id", nullable = false, updatable = false)
  private Long subscriptionId;

  @Column(name = "requested_at", nullable = false, updatable = false)
  private Instant requestedAt;

  @Column(name = "effective_at", nullable = false, updatable = false)
  private Instant effectiveAt;

  @Column(name = "cancelled_by_membership_id", nullable = false, updatable = false)
  private Long cancelledByMembershipId;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  public static SubscriptionCancellation request(
      Long subscriptionId, Long membershipId, Instant requestedAt, Instant effectiveAt) {
    SubscriptionCancellation cancellation = new SubscriptionCancellation();
    cancellation.subscriptionId = subscriptionId;
    cancellation.cancelledByMembershipId = membershipId;
    cancellation.requestedAt = requestedAt;
    cancellation.effectiveAt = effectiveAt;
    return cancellation;
  }

  public boolean isEffective(Instant now) {
    return !now.isBefore(effectiveAt);
  }

  public void revoke(Instant now) {
    this.revokedAt = now;
  }
}
