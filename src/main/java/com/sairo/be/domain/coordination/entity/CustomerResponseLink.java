package com.sairo.be.domain.coordination.entity;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "customer_response_link")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerResponseLink {

  public static final Duration VALIDITY = Duration.ofDays(7);

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "response_id", nullable = false)
  private Long responseId;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "token_hash", nullable = false, length = 64)
  private String tokenHash;

  @Column(name = "issued_at", nullable = false)
  private Instant issuedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  private CustomerResponseLink(Long responseId, String provisionalTokenHash, Instant issuedAt) {
    this.responseId = responseId;
    this.tokenHash = provisionalTokenHash;
    this.issuedAt = issuedAt;
    this.expiresAt = issuedAt.plus(VALIDITY);
  }

  // token_hash is derived from this row's own id (see PublicLinkTokenGenerator), which the
  // database only assigns on insert. Callers insert with a provisional placeholder and then
  // call assignTokenHash once the real id is known, inside the same transaction.
  public static CustomerResponseLink issueWithProvisionalHash(
      Long responseId, String provisionalTokenHash, Instant issuedAt) {
    return new CustomerResponseLink(responseId, provisionalTokenHash, issuedAt);
  }

  public void assignTokenHash(String tokenHash) {
    this.tokenHash = tokenHash;
  }

  public boolean isActive(Instant now) {
    return revokedAt == null && expiresAt.isAfter(now);
  }
}
