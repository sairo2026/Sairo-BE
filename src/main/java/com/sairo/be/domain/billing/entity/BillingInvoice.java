package com.sairo.be.domain.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "billing_invoice")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BillingInvoice {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "office_id", nullable = false, updatable = false)
  private Long officeId;

  @Column(name = "invoice_kind", nullable = false, length = 12, updatable = false)
  private String invoiceKind;

  @Column(name = "subscription_id", updatable = false)
  private Long subscriptionId;

  @Column(name = "billing_period_start", updatable = false)
  private Instant billingPeriodStart;

  @Column(name = "billing_period_end", nullable = false, updatable = false)
  private Instant billingPeriodEnd;

  @Column(nullable = false, updatable = false)
  private long amount;

  @Column(name = "member_count_snapshot", nullable = false, updatable = false)
  private int memberCountSnapshot;

  @Column(name = "unit_price_snapshot", nullable = false, updatable = false)
  private long unitPriceSnapshot;

  @Column(name = "vat_included_amount", nullable = false, updatable = false)
  private long vatIncludedAmount;

  @Column(name = "price_version", nullable = false, length = 40, updatable = false)
  private String priceVersion;

  @Column(name = "consent_version", length = 40, updatable = false)
  private String consentVersion;

  @Column(name = "consent_amount_limit", nullable = false, updatable = false)
  private long consentAmountLimit;

  @Column(name = "consented_at", nullable = false, updatable = false)
  private Instant consentedAt;

  @Column(name = "purchase_consent_id", updatable = false)
  private Long purchaseConsentId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private InvoiceStatus status;

  @Column(name = "due_at", nullable = false, updatable = false)
  private Instant dueAt;

  @Column(name = "paid_at")
  private Instant paidAt;

  @Column(name = "next_retry_at")
  private Instant nextRetryAt;

  @Column(name = "supply_amount")
  private Long supplyAmount;

  @Column(name = "vat_amount")
  private Long vatAmount;

  @Column(name = "vat_rate_bp_snapshot")
  private Integer vatRateBpSnapshot;

  @Column(name = "supplier_info_version")
  private Integer supplierInfoVersion;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public static BillingInvoice issue(IssueCommand command) {
    BillingInvoice invoice = new BillingInvoice();
    invoice.officeId = command.officeId();
    invoice.invoiceKind = "SUBSCRIPTION";
    invoice.subscriptionId = command.subscriptionId();
    invoice.billingPeriodStart = command.periodStart();
    invoice.billingPeriodEnd = command.periodEnd();
    invoice.amount = command.amount();
    invoice.memberCountSnapshot = command.memberCount();
    invoice.unitPriceSnapshot = command.amount();
    invoice.vatIncludedAmount = command.amount();
    invoice.priceVersion = command.priceVersion();
    invoice.consentVersion = command.consentVersion();
    invoice.consentAmountLimit = command.consentAmountLimit();
    invoice.consentedAt = command.consentedAt();
    invoice.purchaseConsentId = command.purchaseConsentId();
    invoice.status = InvoiceStatus.UNBILLED;
    invoice.dueAt = command.dueAt();
    invoice.supplyAmount = command.supplyAmount();
    invoice.vatAmount = command.vatAmount();
    invoice.vatRateBpSnapshot = command.vatRateBp();
    invoice.supplierInfoVersion = command.supplierInfoVersion();
    return invoice;
  }

  public void markProcessing() {
    this.status = InvoiceStatus.PROCESSING;
    this.nextRetryAt = null;
  }

  public void markVerifying() {
    this.status = InvoiceStatus.VERIFYING;
  }

  public void markPaid(Instant now) {
    this.status = InvoiceStatus.PAID;
    this.paidAt = now;
    this.nextRetryAt = null;
  }

  public void markFailed(Instant retryAt) {
    this.status = InvoiceStatus.FAILED;
    this.nextRetryAt = retryAt;
  }

  public void markCancelled() {
    this.status = InvoiceStatus.CANCELLED;
    this.nextRetryAt = null;
  }

  public boolean isUnpaid() {
    return status == InvoiceStatus.UNBILLED || status == InvoiceStatus.FAILED;
  }

  public boolean isRefundableStatus() {
    return status == InvoiceStatus.PAID || status == InvoiceStatus.PARTIALLY_REFUNDED;
  }

  public record IssueCommand(
      Long officeId,
      Long subscriptionId,
      Instant periodStart,
      Instant periodEnd,
      long amount,
      int memberCount,
      String priceVersion,
      String consentVersion,
      long consentAmountLimit,
      Instant consentedAt,
      Long purchaseConsentId,
      Instant dueAt,
      long supplyAmount,
      long vatAmount,
      int vatRateBp,
      Integer supplierInfoVersion) {}

  @PrePersist
  void onCreate() {
    createdAt = Instant.now();
  }
}
