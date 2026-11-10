package com.sairo.be.global.port;

public interface PaymentGateway {

  BillingKeyResult issueBillingKey(String authKey, String customerKey);

  PaymentResult charge(ChargeCommand command);

  PaymentResult confirm(ConfirmCommand command);

  PaymentResult cancel(String paymentKey, String reason);

  PaymentResult inquire(String orderId);

  class BillingKeyIssueException extends RuntimeException {

    public BillingKeyIssueException(String message) {
      super(message);
    }
  }

  enum PaymentStatus {
    SUCCEEDED,
    FAILED,
    VERIFYING
  }

  record BillingKeyResult(String billingKey, String cardIssuer, String maskedCardNumber) {}

  record ChargeCommand(
      String billingKey,
      String customerKey,
      String orderId,
      long amount,
      String orderName,
      String idempotencyKey) {}

  record ConfirmCommand(String paymentKey, String orderId, long amount) {}

  record PaymentResult(PaymentStatus status, String paymentKey, String failureCode) {}
}
