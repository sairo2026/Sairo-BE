package com.sairo.be.domain.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "billing_customer")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BillingCustomer {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "office_id", nullable = false, updatable = false)
  private Long officeId;

  @Column(name = "toss_customer_key", nullable = false, length = 100)
  private String tossCustomerKey;

  @Column(name = "customer_key_issued_at")
  private Instant customerKeyIssuedAt;

  @Column(name = "customer_key_expires_at")
  private Instant customerKeyExpiresAt;

  @Column(name = "billing_key_encrypted", columnDefinition = "text")
  private String billingKeyEncrypted;

  @Column(name = "billing_key_registered_at")
  private Instant billingKeyRegisteredAt;

  @Column(name = "card_issuer", length = 50)
  private String cardIssuer;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "card_last4", length = 4)
  private String cardLast4;

  @Column(name = "previous_billing_key_encrypted", columnDefinition = "text")
  private String previousBillingKeyEncrypted;

  @Column(name = "replaced_at")
  private Instant replacedAt;

  public static BillingCustomer open(
      Long officeId, String customerKey, Instant issuedAt, Instant expiresAt) {
    BillingCustomer customer = new BillingCustomer();
    customer.officeId = officeId;
    customer.tossCustomerKey = customerKey;
    customer.customerKeyIssuedAt = issuedAt;
    customer.customerKeyExpiresAt = expiresAt;
    return customer;
  }

  public boolean hasBillingKey() {
    return billingKeyEncrypted != null;
  }

  public void refreshKeyWindow(Instant issuedAt, Instant expiresAt) {
    this.customerKeyIssuedAt = issuedAt;
    this.customerKeyExpiresAt = expiresAt;
  }

  public void rotateCustomerKey(String customerKey, Instant issuedAt, Instant expiresAt) {
    this.tossCustomerKey = customerKey;
    refreshKeyWindow(issuedAt, expiresAt);
  }

  public boolean isKeyWindowOpen(Instant now) {
    return customerKeyExpiresAt != null && customerKeyExpiresAt.isAfter(now);
  }

  public void registerBillingKey(
      String encryptedKey, String issuer, String last4, Instant registeredAt) {
    if (billingKeyEncrypted != null) {
      this.previousBillingKeyEncrypted = billingKeyEncrypted;
      this.replacedAt = registeredAt;
    }
    this.billingKeyEncrypted = encryptedKey;
    this.billingKeyRegisteredAt = registeredAt;
    this.cardIssuer = issuer;
    this.cardLast4 = last4;
    this.customerKeyIssuedAt = null;
    this.customerKeyExpiresAt = null;
  }

  public void clearBillingKey(Instant now) {
    this.previousBillingKeyEncrypted = billingKeyEncrypted;
    this.replacedAt = now;
    this.billingKeyEncrypted = null;
    this.billingKeyRegisteredAt = null;
    this.cardIssuer = null;
    this.cardLast4 = null;
  }
}
