package com.sairo.be.domain.support.entity;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "enterprise_quote")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EnterpriseQuote {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "lead_id", nullable = false, updatable = false)
  private Long leadId;

  @Column(name = "version_no", nullable = false, updatable = false)
  private int versionNo;

  @Column(nullable = false, updatable = false)
  private long amount;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "accepted_at")
  private Instant acceptedAt;

  @Column(name = "rejected_at")
  private Instant rejectedAt;

  @Column(name = "superseded_at")
  private Instant supersededAt;

  public boolean isSuperseded() {
    return supersededAt != null;
  }

  public boolean hasResponse() {
    return acceptedAt != null || rejectedAt != null;
  }

  public boolean isExpiredAt(Instant now) {
    return !now.isBefore(expiresAt);
  }

  public void accept(Instant now) {
    requireOpen();
    acceptedAt = now.truncatedTo(ChronoUnit.MICROS);
  }

  public void reject(Instant now) {
    requireOpen();
    rejectedAt = now.truncatedTo(ChronoUnit.MICROS);
  }

  private void requireOpen() {
    if (hasResponse()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
  }
}
