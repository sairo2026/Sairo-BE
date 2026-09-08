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
@Table(name = "coordination_customer_response")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CoordinationCustomerResponse {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "coordination_id", nullable = false)
  private Long coordinationId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private CustomerResponseRole role;

  @Column(name = "customer_name", length = 50)
  private String customerName;

  @Column(name = "customer_phone", length = 30)
  private String customerPhone;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private CustomerResponseResult result;

  @Column(name = "submitted_at")
  private Instant submittedAt;

  @Column(name = "reset_count", nullable = false)
  private int resetCount;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  private CoordinationCustomerResponse(
      Long coordinationId, CustomerResponseRole role, String customerName, String customerPhone) {
    this.coordinationId = coordinationId;
    this.role = role;
    this.customerName = customerName;
    this.customerPhone = customerPhone;
    this.result = CustomerResponseResult.WAITING;
    this.resetCount = 0;
  }

  public static CoordinationCustomerResponse waitingForTenant(
      Long coordinationId, String customerName, String customerPhone) {
    return new CoordinationCustomerResponse(
        coordinationId, CustomerResponseRole.TENANT, customerName, customerPhone);
  }

  public boolean isWaiting() {
    return result == CustomerResponseResult.WAITING;
  }

  public boolean isAvailableTimesSubmittable() {
    return result == CustomerResponseResult.WAITING
        || result == CustomerResponseResult.AVAILABLE_SUBMITTED;
  }

  public void submitAvailability(Instant submittedAt) {
    this.result = CustomerResponseResult.AVAILABLE_SUBMITTED;
    this.submittedAt = submittedAt;
  }

  public void submitNoAvailability(Instant submittedAt) {
    this.result = CustomerResponseResult.NONE_AVAILABLE;
    this.submittedAt = submittedAt;
  }
}
