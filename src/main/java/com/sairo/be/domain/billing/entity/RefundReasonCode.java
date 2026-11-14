package com.sairo.be.domain.billing.entity;

public enum RefundReasonCode {
  CANCELLATION(RefundEventType.SUBSCRIPTION_CANCELLATION),
  AUTH_SUSPENSION(RefundEventType.AUTH_SUSPENSION),
  DUPLICATE_CHARGE(RefundEventType.INVOICE),
  INCORRECT_CHARGE(RefundEventType.INVOICE),
  SERVICE_FAILURE(RefundEventType.SERVICE_INCIDENT),
  LATE_APPROVAL(RefundEventType.LATE_APPROVAL),
  POLICY_COMPENSATION(RefundEventType.POLICY_COMPENSATION);

  private final RefundEventType eventType;

  RefundReasonCode(RefundEventType eventType) {
    this.eventType = eventType;
  }

  public RefundEventType eventType() {
    return eventType;
  }

  public boolean isInvoiceEvent() {
    return eventType == RefundEventType.INVOICE;
  }
}
