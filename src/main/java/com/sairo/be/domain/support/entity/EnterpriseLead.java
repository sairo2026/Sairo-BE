package com.sairo.be.domain.support.entity;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "enterprise_lead")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EnterpriseLead {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "company_name", nullable = false, length = 200, updatable = false)
  private String companyName;

  @Column(name = "contact_name", nullable = false, length = 50, updatable = false)
  private String contactName;

  @Column(name = "contact_phone", nullable = false, length = 30, updatable = false)
  private String contactPhone;

  @Column(name = "contact_email", nullable = false, length = 254, updatable = false)
  private String contactEmail;

  @Column(name = "branch_count", nullable = false, updatable = false)
  private int branchCount;

  @Column(name = "expected_seats", nullable = false, updatable = false)
  private int expectedSeats;

  @Column(name = "migration_needed", nullable = false, updatable = false)
  private boolean migrationNeeded;

  @Column(nullable = false, columnDefinition = "text", updatable = false)
  private String content;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "tracking_key_hash", nullable = false, length = 64, updatable = false)
  private String trackingKeyHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 15)
  private LeadStatus status;

  @Column(name = "assigned_operator_id")
  private Long assignedOperatorId;

  @Column(name = "quote_amount")
  private Long quoteAmount;

  @Column(name = "quote_expires_at")
  private Instant quoteExpiresAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private Long version = 1L;

  public static EnterpriseLead receive(
      String companyName,
      String contactName,
      String contactEmail,
      String contactPhone,
      int branchCount,
      int expectedSeats,
      boolean migrationNeeded,
      String content,
      String trackingKeyHash,
      Instant now) {
    Instant at = now.truncatedTo(ChronoUnit.MICROS);
    EnterpriseLead lead = new EnterpriseLead();
    lead.companyName = companyName;
    lead.contactName = contactName;
    lead.contactEmail = contactEmail;
    lead.contactPhone = contactPhone;
    lead.branchCount = branchCount;
    lead.expectedSeats = expectedSeats;
    lead.migrationNeeded = migrationNeeded;
    lead.content = content;
    lead.trackingKeyHash = trackingKeyHash;
    lead.status = LeadStatus.RECEIVED;
    lead.createdAt = at;
    lead.updatedAt = at;
    return lead;
  }

  public void customerAccepted(Instant now) {
    requireQuoted();
    status = LeadStatus.ACCEPTED;
    updatedAt = now.truncatedTo(ChronoUnit.MICROS);
  }

  public void customerRejected(Instant now) {
    requireQuoted();
    status = LeadStatus.REJECTED;
    updatedAt = now.truncatedTo(ChronoUnit.MICROS);
  }

  private void requireQuoted() {
    if (status != LeadStatus.QUOTED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
  }
}
