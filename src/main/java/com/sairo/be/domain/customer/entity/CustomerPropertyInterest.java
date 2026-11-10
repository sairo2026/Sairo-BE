package com.sairo.be.domain.customer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "customer_property_interest")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerPropertyInterest {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "office_id", nullable = false, updatable = false)
  private Long officeId;

  @Column(name = "customer_id", nullable = false, updatable = false)
  private Long customerId;

  @Column(name = "property_id", nullable = false, updatable = false)
  private Long propertyId;

  @Column(name = "start_date", nullable = false, updatable = false)
  private LocalDate startDate;

  @Column(name = "end_date")
  private LocalDate endDate;

  @Column(columnDefinition = "text")
  private String note;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public static CustomerPropertyInterest open(
      Long officeId,
      Long customerId,
      Long propertyId,
      LocalDate startDate,
      LocalDate endDate,
      String note) {
    CustomerPropertyInterest interest = new CustomerPropertyInterest();
    interest.officeId = officeId;
    interest.customerId = customerId;
    interest.propertyId = propertyId;
    interest.startDate = startDate;
    interest.endDate = endDate;
    interest.note = note;
    return interest;
  }

  public void change(LocalDate endDate, String note) {
    this.endDate = endDate;
    this.note = note;
  }

  @PrePersist
  void onCreate() {
    createdAt = Instant.now();
  }
}
