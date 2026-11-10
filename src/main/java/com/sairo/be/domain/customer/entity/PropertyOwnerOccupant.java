package com.sairo.be.domain.customer.entity;

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
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "property_owner_occupant")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PropertyOwnerOccupant {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "office_id", nullable = false, updatable = false)
  private Long officeId;

  @Column(name = "property_id", nullable = false, updatable = false)
  private Long propertyId;

  @Column(name = "customer_id", nullable = false, updatable = false)
  private Long customerId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10, updatable = false)
  private OwnerRole role;

  @Column(name = "start_date", nullable = false)
  private LocalDate startDate;

  @Column(name = "end_date")
  private LocalDate endDate;

  @Column(name = "confirmed_by_membership_id")
  private Long confirmedByMembershipId;

  @Column(name = "confirmed_at")
  private Instant confirmedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public static PropertyOwnerOccupant link(
      Long officeId,
      Long propertyId,
      Long customerId,
      OwnerRole role,
      LocalDate startDate,
      Long confirmedByMembershipId,
      Instant confirmedAt) {
    PropertyOwnerOccupant link = new PropertyOwnerOccupant();
    link.officeId = officeId;
    link.propertyId = propertyId;
    link.customerId = customerId;
    link.role = role;
    link.startDate = startDate;
    link.confirmedByMembershipId = confirmedByMembershipId;
    link.confirmedAt = confirmedAt;
    return link;
  }

  public void changeEndDate(LocalDate endDate) {
    this.endDate = endDate;
  }

  @PrePersist
  void onCreate() {
    createdAt = Instant.now();
  }
}
