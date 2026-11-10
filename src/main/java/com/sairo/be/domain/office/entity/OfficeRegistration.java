package com.sairo.be.domain.office.entity;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
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
@Table(name = "office_registration")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OfficeRegistration {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "applicant_user_id", nullable = false, updatable = false)
  private Long applicantUserId;

  @Enumerated(EnumType.STRING)
  @Column(name = "entity_type", nullable = false, length = 12)
  private OfficeEntityType entityType;

  @Column(name = "registration_authority", nullable = false, length = 100)
  private String registrationAuthority;

  @Column(name = "real_estate_license_number", nullable = false, length = 30)
  private String realEstateLicenseNumber;

  @Column(name = "business_registration_number", nullable = false, length = 10)
  private String businessRegistrationNumber;

  @Column(name = "requested_office_name", nullable = false, length = 100)
  private String requestedOfficeName;

  @Column(name = "requested_representative_name", nullable = false, length = 50)
  private String requestedRepresentativeName;

  @Column(name = "requested_representative_phone", nullable = false, length = 30)
  private String requestedRepresentativePhone;

  @Column(name = "requested_address", nullable = false, length = 300)
  private String requestedAddress;

  @Column(name = "applicant_registration_relation", nullable = false, length = 30)
  private String applicantRegistrationRelation;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private RegistrationStatus status;

  @Column(name = "resulting_office_id")
  private Long resultingOfficeId;

  @Column(name = "current_revision_no", nullable = false)
  private int currentRevisionNo = 1;

  @Column(name = "supplement_reason", columnDefinition = "text")
  private String supplementReason;

  @Column(name = "supplement_due_at")
  private Instant supplementDueAt;

  @Column(name = "rejection_reason", columnDefinition = "text")
  private String rejectionReason;

  @Column(name = "revocation_reason", columnDefinition = "text")
  private String revocationReason;

  @Column(name = "reviewed_at")
  private Instant reviewedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "cancelled_at")
  private Instant cancelledAt;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private Long version = 1L;

  public record Draft(
      Long applicantUserId,
      OfficeEntityType entityType,
      String registrationAuthority,
      String realEstateLicenseNumber,
      String businessRegistrationNumber,
      String requestedOfficeName,
      String requestedRepresentativeName,
      String requestedRepresentativePhone,
      String requestedAddress,
      String applicantRegistrationRelation) {}

  public static OfficeRegistration draft(Draft draft) {
    OfficeRegistration registration = new OfficeRegistration();
    registration.applicantUserId = draft.applicantUserId();
    registration.entityType = draft.entityType();
    registration.registrationAuthority = draft.registrationAuthority();
    registration.realEstateLicenseNumber = draft.realEstateLicenseNumber();
    registration.businessRegistrationNumber = draft.businessRegistrationNumber();
    registration.requestedOfficeName = draft.requestedOfficeName();
    registration.requestedRepresentativeName = draft.requestedRepresentativeName();
    registration.requestedRepresentativePhone = draft.requestedRepresentativePhone();
    registration.requestedAddress = draft.requestedAddress();
    registration.applicantRegistrationRelation = draft.applicantRegistrationRelation();
    registration.status = RegistrationStatus.DRAFT;
    return registration;
  }

  public boolean acceptsEvidence() {
    return status == RegistrationStatus.DRAFT || status == RegistrationStatus.SUPPLEMENT_REQUESTED;
  }

  public int submitEvidence() {
    if (!acceptsEvidence()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    if (status == RegistrationStatus.SUPPLEMENT_REQUESTED) {
      currentRevisionNo += 1;
      supplementReason = null;
      supplementDueAt = null;
      reviewedAt = null;
    }
    status = RegistrationStatus.PENDING_REVIEW;
    return currentRevisionNo;
  }

  public void cancel(Instant now) {
    if (!status.isInProgress()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    status = RegistrationStatus.CANCELLED;
    cancelledAt = now;
    supplementReason = null;
    supplementDueAt = null;
  }

  public void resolveRejection(Instant now) {
    if (status != RegistrationStatus.REJECTED || resolvedAt != null) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    resolvedAt = now;
  }

  public boolean isResolved() {
    return resolvedAt != null;
  }

  public void requestSupplement(String reason, Instant dueAt, Instant now) {
    if (status != RegistrationStatus.PENDING_REVIEW) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    status = RegistrationStatus.SUPPLEMENT_REQUESTED;
    supplementReason = reason;
    supplementDueAt = dueAt;
    reviewedAt = now;
  }

  public void approve(Long officeId, Instant now) {
    if (status != RegistrationStatus.PENDING_REVIEW) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    status = RegistrationStatus.APPROVED;
    resultingOfficeId = officeId;
    reviewedAt = now;
  }

  public void reject(String reason, Instant now) {
    if (status != RegistrationStatus.PENDING_REVIEW
        && status != RegistrationStatus.SUPPLEMENT_REQUESTED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    status = RegistrationStatus.REJECTED;
    rejectionReason = reason;
    supplementReason = null;
    supplementDueAt = null;
    reviewedAt = now;
  }

  public void revoke(String reason, Instant now) {
    if (status != RegistrationStatus.APPROVED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    status = RegistrationStatus.REVOKED;
    revocationReason = reason;
    revokedAt = now;
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
