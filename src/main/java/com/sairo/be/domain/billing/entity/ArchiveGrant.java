package com.sairo.be.domain.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "archive_grant")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ArchiveGrant {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "office_id", nullable = false, updatable = false)
  private Long officeId;

  @Column(name = "granted_to_user_id", nullable = false, updatable = false)
  private Long grantedToUserId;

  @Column(name = "contract_reference", columnDefinition = "text", updatable = false)
  private String contractReference;

  @Column(name = "granted_at", nullable = false, updatable = false)
  private Instant grantedAt;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "terminated_at")
  private Instant terminatedAt;

  @Column(name = "terminated_reason", columnDefinition = "text")
  private String terminatedReason;

  public static ArchiveGrant issue(
      Long officeId, Long userId, String contractReference, Instant grantedAt) {
    ArchiveGrant grant = new ArchiveGrant();
    grant.officeId = officeId;
    grant.grantedToUserId = userId;
    grant.contractReference = contractReference;
    grant.grantedAt = grantedAt;
    return grant;
  }

  public boolean isUsable(Instant now) {
    return terminatedAt == null && (expiresAt == null || now.isBefore(expiresAt));
  }
}
