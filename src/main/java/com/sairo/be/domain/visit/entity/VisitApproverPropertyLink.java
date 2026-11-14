package com.sairo.be.domain.visit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "visit_approver_property_link")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VisitApproverPropertyLink {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "approval_id", nullable = false, updatable = false)
  private Long approvalId;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "token_hash", length = 64)
  private String tokenHash;

  @Column(name = "issued_by_membership_id", nullable = false, updatable = false)
  private Long issuedByMembershipId;

  @Column(name = "issued_at", nullable = false, updatable = false)
  private Instant issuedAt;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "last_accessed_at")
  private Instant lastAccessedAt;

  @Column(name = "retention_until", nullable = false)
  private LocalDate retentionUntil;

  @Column(name = "anonymized_at")
  private Instant anonymizedAt;

  public static VisitApproverPropertyLink issue(
      Long approvalId,
      String placeholderHash,
      Long issuedByMembershipId,
      Instant issuedAt,
      Instant expiresAt,
      LocalDate retentionUntil) {
    VisitApproverPropertyLink link = new VisitApproverPropertyLink();
    link.approvalId = approvalId;
    link.tokenHash = placeholderHash;
    link.issuedByMembershipId = issuedByMembershipId;
    link.issuedAt = issuedAt;
    link.expiresAt = expiresAt;
    link.retentionUntil = retentionUntil;
    return link;
  }

  public void bindToken(String tokenHash) {
    this.tokenHash = tokenHash;
  }

  public void revoke(Instant at) {
    if (revokedAt == null) {
      revokedAt = at;
    }
  }

  public void touch(Instant at) {
    lastAccessedAt = at;
  }

  public boolean isExpiredAt(Instant now) {
    return !now.isBefore(expiresAt);
  }
}
