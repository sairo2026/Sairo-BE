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
@Table(name = "visit_customer_link")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VisitCustomerLink {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "coordination_id", nullable = false, updatable = false)
  private Long coordinationId;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "token_hash", nullable = false, length = 64)
  private String tokenHash;

  @Column(name = "issued_at", nullable = false, updatable = false)
  private Instant issuedAt;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "response_version", nullable = false)
  private int responseVersion;

  @Column(name = "responded_at")
  private Instant respondedAt;

  @Column(name = "retention_until", nullable = false)
  private LocalDate retentionUntil;

  public static VisitCustomerLink issue(
      Long coordinationId,
      String placeholderHash,
      Instant issuedAt,
      Instant expiresAt,
      LocalDate retentionUntil) {
    VisitCustomerLink link = new VisitCustomerLink();
    link.coordinationId = coordinationId;
    link.tokenHash = placeholderHash;
    link.issuedAt = issuedAt;
    link.expiresAt = expiresAt;
    link.retentionUntil = retentionUntil;
    return link;
  }

  public void bindToken(String tokenHash) {
    this.tokenHash = tokenHash;
  }

  public void inheritResponse(VisitCustomerLink previous) {
    this.responseVersion = previous.responseVersion;
    this.respondedAt = previous.respondedAt;
  }

  public void revoke(Instant at) {
    if (revokedAt == null) {
      revokedAt = at;
    }
  }

  public boolean isRevoked() {
    return revokedAt != null;
  }

  public boolean isExpiredAt(Instant now) {
    return !now.isBefore(expiresAt);
  }

  public int recordResponse(Instant at) {
    responseVersion += 1;
    respondedAt = at.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    return responseVersion;
  }
}
