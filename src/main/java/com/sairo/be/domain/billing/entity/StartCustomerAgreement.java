package com.sairo.be.domain.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "billing_start_customer_agreement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StartCustomerAgreement {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "office_id", nullable = false, updatable = false)
  private Long officeId;

  @Column(name = "trial_event_id", nullable = false, updatable = false)
  private Long trialEventId;

  @Column(name = "price_schedule_id", nullable = false, updatable = false)
  private Long priceScheduleId;

  @Column(name = "price_starts_at", nullable = false, updatable = false)
  private Instant priceStartsAt;

  @Column(name = "price_ends_at", nullable = false, updatable = false)
  private Instant priceEndsAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 12)
  private AgreementStatus status;

  @Column(name = "contracted_on", nullable = false, updatable = false)
  private LocalDate contractedOn;

  @Column(name = "weekly_use_confirmed", nullable = false, updatable = false)
  private boolean weeklyUseConfirmed;

  @Column(name = "monthly_interview_confirmed", nullable = false, updatable = false)
  private boolean monthlyInterviewConfirmed;

  @Column(name = "case_publication_consent", nullable = false, updatable = false)
  private boolean casePublicationConsent;

  @Column(name = "agreement_text_version", nullable = false, length = 20, updatable = false)
  private String agreementTextVersion;

  @Column(name = "owner_acknowledged_at")
  private Instant ownerAcknowledgedAt;

  @Column(name = "owner_acknowledged_by_membership_id")
  private Long ownerAcknowledgedByMembershipId;

  @Column(name = "approved_at")
  private Instant approvedAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private Long version = 1L;

  public boolean isApproved() {
    return status == AgreementStatus.APPROVED;
  }

  public boolean isAcknowledged() {
    return ownerAcknowledgedAt != null;
  }

  public void acknowledge(Long membershipId, Instant now) {
    this.ownerAcknowledgedAt = now;
    this.ownerAcknowledgedByMembershipId = membershipId;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }
}
