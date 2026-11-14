package com.sairo.be.domain.billing.entity;

public enum InvoiceStatus {
  UNBILLED,
  PROCESSING,
  VERIFYING,
  PAID,
  FAILED,
  PARTIALLY_REFUNDED,
  FULLY_REFUNDED,
  CANCELLED
}
