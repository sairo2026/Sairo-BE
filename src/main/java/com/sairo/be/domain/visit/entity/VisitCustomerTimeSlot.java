package com.sairo.be.domain.visit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "visit_customer_time_slot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VisitCustomerTimeSlot {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "customer_link_id", nullable = false, updatable = false)
  private Long customerLinkId;

  @Column(name = "response_version", nullable = false, updatable = false)
  private int responseVersion;

  @Column(name = "starts_at", nullable = false, updatable = false)
  private Instant startsAt;

  @Column(name = "ends_at", nullable = false, updatable = false)
  private Instant endsAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public static VisitCustomerTimeSlot of(
      Long customerLinkId, int responseVersion, Instant startsAt, Instant endsAt) {
    VisitCustomerTimeSlot slot = new VisitCustomerTimeSlot();
    slot.customerLinkId = customerLinkId;
    slot.responseVersion = responseVersion;
    slot.startsAt = startsAt;
    slot.endsAt = endsAt;
    return slot;
  }

  @PrePersist
  void onCreate() {
    createdAt = Instant.now();
  }
}
