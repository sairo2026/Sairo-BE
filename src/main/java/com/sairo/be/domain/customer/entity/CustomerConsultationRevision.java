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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "customer_consultation_revision")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerConsultationRevision {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "consultation_id", nullable = false, updatable = false)
  private Long consultationId;

  @Column(name = "previous_occurred_at", nullable = false, updatable = false)
  private Instant previousOccurredAt;

  @Column(name = "previous_content", nullable = false, columnDefinition = "text", updatable = false)
  private String previousContent;

  @Enumerated(EnumType.STRING)
  @Column(name = "previous_consultation_type", nullable = false, length = 16, updatable = false)
  private ConsultationType previousConsultationType;

  @Column(
      name = "correction_reason",
      nullable = false,
      columnDefinition = "text",
      updatable = false)
  private String correctionReason;

  @Column(name = "corrected_by_membership_id", nullable = false, updatable = false)
  private Long correctedByMembershipId;

  @Column(name = "corrected_at", nullable = false, updatable = false)
  private Instant correctedAt;

  public static CustomerConsultationRevision preserve(
      CustomerConsultation before, String reason, Long correctedByMembershipId) {
    CustomerConsultationRevision revision = new CustomerConsultationRevision();
    revision.consultationId = before.getId();
    revision.previousOccurredAt = before.getOccurredAt();
    revision.previousContent = before.getContent();
    revision.previousConsultationType = before.getConsultationType();
    revision.correctionReason = reason;
    revision.correctedByMembershipId = correctedByMembershipId;
    return revision;
  }

  @PrePersist
  void onCreate() {
    correctedAt = Instant.now();
  }
}
