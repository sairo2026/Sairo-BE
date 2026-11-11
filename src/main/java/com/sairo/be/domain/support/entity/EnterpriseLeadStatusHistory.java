package com.sairo.be.domain.support.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "enterprise_lead_status_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EnterpriseLeadStatusHistory {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "lead_id", nullable = false, updatable = false)
  private Long leadId;

  @Column(name = "event_type", nullable = false, length = 25, updatable = false)
  private String eventType;

  @Enumerated(EnumType.STRING)
  @Column(name = "from_status", length = 15, updatable = false)
  private LeadStatus fromStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "to_status", nullable = false, length = 15, updatable = false)
  private LeadStatus toStatus;

  @Column(name = "actor_type", nullable = false, length = 10, updatable = false)
  private String actorType;

  @Column(name = "quote_id", updatable = false)
  private Long quoteId;

  @Column(columnDefinition = "text", updatable = false)
  private String reason;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public static EnterpriseLeadStatusHistory received(Long leadId, Instant now) {
    return of(leadId, "RECEIVED", null, LeadStatus.RECEIVED, null, now);
  }

  public static EnterpriseLeadStatusHistory quoteAccepted(Long leadId, Long quoteId, Instant now) {
    return of(leadId, "QUOTE_ACCEPTED", LeadStatus.QUOTED, LeadStatus.ACCEPTED, quoteId, now);
  }

  public static EnterpriseLeadStatusHistory quoteRejected(Long leadId, Long quoteId, Instant now) {
    return of(leadId, "QUOTE_REJECTED", LeadStatus.QUOTED, LeadStatus.REJECTED, quoteId, now);
  }

  private static EnterpriseLeadStatusHistory of(
      Long leadId, String eventType, LeadStatus from, LeadStatus to, Long quoteId, Instant now) {
    EnterpriseLeadStatusHistory history = new EnterpriseLeadStatusHistory();
    history.leadId = leadId;
    history.eventType = eventType;
    history.fromStatus = from;
    history.toStatus = to;
    history.actorType = "CUSTOMER";
    history.quoteId = quoteId;
    history.createdAt = now.truncatedTo(ChronoUnit.MICROS);
    return history;
  }
}
