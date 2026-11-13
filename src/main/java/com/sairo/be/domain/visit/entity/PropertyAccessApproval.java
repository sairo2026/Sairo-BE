package com.sairo.be.domain.visit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "property_access_approval")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PropertyAccessApproval {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "property_id", nullable = false, updatable = false)
  private Long propertyId;

  @Enumerated(EnumType.STRING)
  @Column(name = "approver_type", nullable = false, length = 10)
  private ApproverType approverType;

  @Column(name = "approver_name", length = 50)
  private String approverName;

  @Column(name = "approver_phone_encrypted")
  private String approverPhoneEncrypted;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "approver_phone_hash", length = 64)
  private String approverPhoneHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "approval_role", nullable = false, length = 12)
  private ApprovalRole approvalRole;

  @Column(name = "valid_from", nullable = false)
  private Instant validFrom;

  @Column(name = "valid_until", nullable = false)
  private Instant validUntil;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private InputSource source;

  @Column(name = "recorded_by_membership_id")
  private Long recordedByMembershipId;

  @Column(name = "confirmed_at")
  private Instant confirmedAt;

  @Column(name = "recorded_at", nullable = false, updatable = false)
  private Instant recordedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "revocation_reason", columnDefinition = "text")
  private String revocationReason;

  @Column(name = "revocation_recorded_at")
  private Instant revocationRecordedAt;

  @Column(name = "revoked_by_user_id")
  private Long revokedByUserId;

  @Column(name = "retention_until", nullable = false)
  private LocalDate retentionUntil;

  @Column(name = "anonymized_at")
  private Instant anonymizedAt;

  public static PropertyAccessApproval register(
      Long propertyId,
      ApproverType approverType,
      String approverName,
      String approverPhoneEncrypted,
      String approverPhoneHash,
      ApprovalRole approvalRole,
      Instant validFrom,
      Instant validUntil,
      InputSource source,
      Long recordedByMembershipId,
      Instant confirmedAt,
      Instant recordedAt,
      LocalDate retentionUntil) {
    PropertyAccessApproval approval = new PropertyAccessApproval();
    approval.propertyId = propertyId;
    approval.approverType = approverType;
    approval.approverName = approverName;
    approval.approverPhoneEncrypted = approverPhoneEncrypted;
    approval.approverPhoneHash = approverPhoneHash;
    approval.approvalRole = approvalRole;
    approval.validFrom = validFrom;
    approval.validUntil = validUntil;
    approval.source = source;
    approval.recordedByMembershipId = recordedByMembershipId;
    approval.confirmedAt = confirmedAt;
    approval.recordedAt = recordedAt;
    approval.retentionUntil = retentionUntil;
    return approval;
  }

  public void revoke(Instant revokedAt, String reason, Instant recordedAt, Long userId) {
    this.revokedAt = revokedAt;
    this.revocationReason = reason;
    this.revocationRecordedAt = recordedAt;
    this.revokedByUserId = userId;
  }

  public boolean isRevoked() {
    return revokedAt != null;
  }

  public boolean isUsableAt(Instant now) {
    return revokedAt == null && anonymizedAt == null && now.isBefore(validUntil);
  }

  public boolean isPrimary() {
    return approvalRole == ApprovalRole.PRIMARY;
  }
}
