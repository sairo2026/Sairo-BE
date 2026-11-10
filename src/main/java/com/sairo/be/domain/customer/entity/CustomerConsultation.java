package com.sairo.be.domain.customer.entity;

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
@Table(name = "customer_consultation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerConsultation {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "customer_id", nullable = false, updatable = false)
  private Long customerId;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Column(name = "author_membership_id", nullable = false, updatable = false)
  private Long authorMembershipId;

  @Column(nullable = false, columnDefinition = "text")
  private String content;

  @Enumerated(EnumType.STRING)
  @Column(name = "consultation_type", nullable = false, length = 16)
  private ConsultationType consultationType;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private Long version = 1L;

  public static CustomerConsultation record(
      Long customerId,
      Instant occurredAt,
      Long authorMembershipId,
      String content,
      ConsultationType type) {
    CustomerConsultation consultation = new CustomerConsultation();
    consultation.customerId = customerId;
    consultation.occurredAt = occurredAt;
    consultation.authorMembershipId = authorMembershipId;
    consultation.content = content;
    consultation.consultationType = type;
    return consultation;
  }

  public void correct(Instant occurredAt, String content, ConsultationType type) {
    this.occurredAt = occurredAt;
    this.content = content;
    this.consultationType = type;
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
