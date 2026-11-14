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
import java.time.LocalDate;
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

  @Column(name = "office_count", nullable = false, updatable = false)
  private int officeCount;

  @Column(name = "seat_count", nullable = false, updatable = false)
  private int seatCount;

  @Column(name = "service_scope", nullable = false, columnDefinition = "text", updatable = false)
  private String serviceScope;

  @Column(name = "tax_amount", nullable = false, updatable = false)
  private long taxAmount;

  @Column(name = "delivery_due_at", nullable = false, updatable = false)
  private LocalDate deliveryDueAt;

  @Column(name = "payment_method", nullable = false, length = 30, updatable = false)
  private String paymentMethod;

  @Column(name = "refund_terms", nullable = false, columnDefinition = "text", updatable = false)
  private String refundTerms;

  @Column(name = "issued_at", nullable = false, updatable = false)
  private Instant issuedAt;

  @Column(name = "issued_by_operator_id", nullable = false, updatable = false)
  private Long issuedByOperatorId;

  @Column(name = "billing_invoice_id", insertable = false, updatable = false)
  private Long billingInvoiceId;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "accepted_at")
  private Instant acceptedAt;

  @Column(name = "rejected_at")
  private Instant rejectedAt;

  @Column(name = "superseded_at")
  private Instant supersededAt;

  public record Terms(
      int officeCount,
      int seatCount,
      long amount,
      String serviceScope,
      long taxAmount,
      LocalDate deliveryDueAt,
      String paymentMethod,
      String refundTerms) {}

  public static EnterpriseQuote issue(
      Long leadId, int versionNo, Terms terms, Long operatorId, Instant expiresAt, Instant now) {
    EnterpriseQuote quote = new EnterpriseQuote();
    quote.leadId = leadId;
    quote.versionNo = versionNo;
    quote.officeCount = terms.officeCount();
    quote.seatCount = terms.seatCount();
    quote.amount = terms.amount();
    quote.serviceScope = terms.serviceScope();
    quote.taxAmount = terms.taxAmount();
    quote.deliveryDueAt = terms.deliveryDueAt();
    quote.paymentMethod = terms.paymentMethod();
    quote.refundTerms = terms.refundTerms();
    quote.issuedByOperatorId = operatorId;
    quote.issuedAt = now.truncatedTo(ChronoUnit.MICROS);
    quote.expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
    return quote;
  }

  public void supersede(Instant now) {
    if (supersededAt == null) {
      supersededAt = now.truncatedTo(ChronoUnit.MICROS);
    }
  }

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
