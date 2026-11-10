package com.sairo.be.domain.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "return_grant")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReturnGrant {

  public static final Duration VALIDITY = Duration.ofDays(30);

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "office_id", nullable = false, updatable = false)
  private Long officeId;

  @Column(name = "granted_to_user_id", nullable = false, updatable = false)
  private Long grantedToUserId;

  @Column(name = "granted_at", nullable = false, updatable = false)
  private Instant grantedAt;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "revoked_reason", columnDefinition = "text")
  private String revokedReason;

  @Column(name = "replaced_by_grant_id")
  private Long replacedByGrantId;

  public static ReturnGrant issue(Long officeId, Long userId, Instant grantedAt) {
    ReturnGrant grant = new ReturnGrant();
    grant.officeId = officeId;
    grant.grantedToUserId = userId;
    grant.grantedAt = grantedAt;
    grant.expiresAt = grantedAt.plus(VALIDITY);
    return grant;
  }

  public boolean isUsable(Instant now) {
    return revokedAt == null && now.isBefore(expiresAt);
  }

  public void revoke(String reason, Long replacedBy, Instant now) {
    this.revokedAt = now;
    this.revokedReason = reason;
    this.replacedByGrantId = replacedBy;
  }
}
