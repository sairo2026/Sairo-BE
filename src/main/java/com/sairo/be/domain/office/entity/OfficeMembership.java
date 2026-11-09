package com.sairo.be.domain.office.entity;

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
@Table(name = "office_membership")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OfficeMembership {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "office_id", nullable = false)
  private Long officeId;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private OfficeMembershipRole role;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private OfficeMembershipStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "request_source", nullable = false, length = 12)
  private OfficeRequestSource requestSource;

  @Column(name = "invitation_accepted_at")
  private Instant invitationAcceptedAt;

  @Column(name = "invitation_expires_at")
  private Instant invitationExpiresAt;

  @Column(name = "qualification_number", length = 30)
  private String qualificationNumber;

  @Column(name = "employment_report_confirmed")
  private Boolean employmentReportConfirmed;

  @Column(name = "training_confirmed")
  private Boolean trainingConfirmed;

  @Column(name = "approved_at")
  private Instant approvedAt;

  @Column(name = "rejection_reason", columnDefinition = "text")
  private String rejectionReason;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "revocation_reason", columnDefinition = "text")
  private String revocationReason;

  @Enumerated(EnumType.STRING)
  @Column(name = "revoked_by_type", length = 10)
  private OfficeMembershipRevoker revokedByType;

  @Column(name = "cancelled_at")
  private Instant cancelledAt;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  @Column(name = "suspended_at")
  private Instant suspendedAt;

  @Column(name = "suspension_reason", columnDefinition = "text")
  private String suspensionReason;

  @Column(name = "expired_at")
  private Instant expiredAt;

  @Column(name = "withdrawn_alias_no")
  private Integer withdrawnAliasNo;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private Long version = 1L;

  public static OfficeMembership approve(
      Long userId, Long officeId, OfficeMembershipRole role, OfficeRequestSource requestSource) {
    OfficeMembership membership = new OfficeMembership();
    membership.userId = userId;
    membership.officeId = officeId;
    membership.role = role;
    membership.status = OfficeMembershipStatus.APPROVED;
    membership.requestSource = requestSource;
    membership.approvedAt = Instant.now();
    return membership;
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
