package com.sairo.be.domain.visit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "customer_visit_preference")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerVisitPreference {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "office_id", nullable = false, updatable = false)
  private Long officeId;

  @Column(name = "customer_id", nullable = false, updatable = false)
  private Long customerId;

  @Column(name = "preferred_weekdays", nullable = false, length = 64)
  private String preferredWeekdays;

  @Column(name = "preferred_start_time")
  private LocalTime preferredStartTime;

  @Column(name = "preferred_end_time")
  private LocalTime preferredEndTime;

  @Column(nullable = false)
  private boolean active;

  @Column(name = "retention_until", nullable = false)
  private LocalDate retentionUntil;

  @Column(name = "confirmed_at", nullable = false)
  private Instant confirmedAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private Long version = 1L;

  public static CustomerVisitPreference create(Long officeId, Long customerId) {
    CustomerVisitPreference preference = new CustomerVisitPreference();
    preference.officeId = officeId;
    preference.customerId = customerId;
    preference.preferredWeekdays = "";
    preference.active = true;
    return preference;
  }

  public void confirm(
      String preferredWeekdays,
      LocalTime preferredStartTime,
      LocalTime preferredEndTime,
      LocalDate retentionUntil,
      Instant now) {
    this.preferredWeekdays = preferredWeekdays;
    this.preferredStartTime = preferredStartTime;
    this.preferredEndTime = preferredEndTime;
    this.active = true;
    this.retentionUntil = retentionUntil;
    this.confirmedAt = now;
    this.updatedAt = now;
  }

  public void deactivate(Instant now) {
    this.preferredWeekdays = "";
    this.preferredStartTime = null;
    this.preferredEndTime = null;
    this.active = false;
    this.updatedAt = now;
  }
}
