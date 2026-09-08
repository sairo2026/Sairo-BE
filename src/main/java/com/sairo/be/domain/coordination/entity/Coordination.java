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
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "coordination")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coordination {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "office_id", nullable = false)
  private Long officeId;

  @Column(name = "property_id", nullable = false)
  private Long propertyId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 40)
  private CoordinationStatus status;

  @Column(name = "selected_buyer_response_id")
  private Long selectedBuyerResponseId;

  @Column(name = "confirmed_candidate_time_id")
  private Long confirmedCandidateTimeId;

  @Column(name = "scheduled_at")
  private Instant scheduledAt;

  @Column(name = "confirmed_at")
  private Instant confirmedAt;

  @Column(name = "created_by_membership_id", nullable = false)
  private Long createdByMembershipId;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  private Coordination(Long officeId, Long propertyId, Long createdByMembershipId) {
    this.officeId = officeId;
    this.propertyId = propertyId;
    this.createdByMembershipId = createdByMembershipId;
    this.status = CoordinationStatus.TENANT_CHECKING;
  }

  public static Coordination startForTenant(
      Long officeId, Long propertyId, Long createdByMembershipId) {
    return new Coordination(officeId, propertyId, createdByMembershipId);
  }

  public void receiveTenantAvailability() {
    this.status = CoordinationStatus.BUYER_DELIVERY_REQUIRED;
  }

  public void receiveFirstBuyerAvailability() {
    this.status = CoordinationStatus.FINAL_CONFIRMATION_REQUIRED;
  }
}
