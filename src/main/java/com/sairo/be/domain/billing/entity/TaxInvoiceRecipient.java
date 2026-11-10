package com.sairo.be.domain.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "tax_invoice_recipient")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaxInvoiceRecipient {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "office_id", nullable = false, updatable = false)
  private Long officeId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 12)
  private RecipientStatus status;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "business_number", nullable = false, length = 10, updatable = false)
  private String businessNumber;

  @Column(name = "company_name", nullable = false, length = 100, updatable = false)
  private String companyName;

  @Column(name = "representative_name", nullable = false, length = 50, updatable = false)
  private String representativeName;

  @Column(name = "receiver_email", nullable = false, length = 254, updatable = false)
  private String receiverEmail;

  @Enumerated(EnumType.STRING)
  @Column(name = "tax_type", nullable = false, length = 12, updatable = false)
  private TaxType taxType;

  @Column(name = "issue_requested", nullable = false, updatable = false)
  private boolean issueRequested;

  @Column(name = "registered_by_membership_id", nullable = false, updatable = false)
  private Long registeredByMembershipId;

  @Column(name = "registered_at", nullable = false, updatable = false)
  private Instant registeredAt;

  @Column(name = "superseded_at")
  private Instant supersededAt;

  public static TaxInvoiceRecipient register(
      Long officeId,
      String businessNumber,
      String companyName,
      String representativeName,
      String receiverEmail,
      TaxType taxType,
      boolean issueRequested,
      Long membershipId,
      Instant now) {
    TaxInvoiceRecipient recipient = new TaxInvoiceRecipient();
    recipient.officeId = officeId;
    recipient.status = RecipientStatus.ACTIVE;
    recipient.businessNumber = businessNumber;
    recipient.companyName = companyName;
    recipient.representativeName = representativeName;
    recipient.receiverEmail = receiverEmail;
    recipient.taxType = taxType;
    recipient.issueRequested = issueRequested;
    recipient.registeredByMembershipId = membershipId;
    recipient.registeredAt = now;
    return recipient;
  }

  public void supersede(Instant now) {
    this.status = RecipientStatus.SUPERSEDED;
    this.supersededAt = now;
  }
}
