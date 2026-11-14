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
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "office_change_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OfficeChangeRequest {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "office_id", nullable = false, updatable = false)
  private Long officeId;

  @Column(name = "requested_by_membership_id", nullable = false, updatable = false)
  private Long requestedByMembershipId;

  @Enumerated(EnumType.STRING)
  @Column(name = "change_type", nullable = false, length = 20)
  private OfficeChangeType changeType;

  @Column(name = "requested_values", nullable = false, columnDefinition = "text")
  private String requestedValues;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 15)
  private ChangeRequestStatus status;

  @Column(name = "reviewed_at")
  private Instant reviewedAt;

  @Column(name = "rejection_reason", columnDefinition = "text")
  private String rejectionReason;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public static OfficeChangeRequest submit(
      Long officeId, Long membershipId, OfficeChangeType changeType, String requestedValues) {
    OfficeChangeRequest request = new OfficeChangeRequest();
    request.officeId = officeId;
    request.requestedByMembershipId = membershipId;
    request.changeType = changeType;
    request.requestedValues = requestedValues;
    request.status = ChangeRequestStatus.PENDING_REVIEW;
    return request;
  }

  public void approve(Instant now) {
    requirePending();
    status = ChangeRequestStatus.APPROVED;
    reviewedAt = now;
  }

  public void reject(String reason, Instant now) {
    requirePending();
    status = ChangeRequestStatus.REJECTED;
    rejectionReason = reason;
    reviewedAt = now;
  }

  private void requirePending() {
    if (status != ChangeRequestStatus.PENDING_REVIEW) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
  }

  @PrePersist
  void onCreate() {
    createdAt = Instant.now();
  }
}
