package com.sairo.be.global.port;

public interface PaymentGateway {

  BillingKeyResult issueBillingKey(String authKey, String customerKey);

  PaymentResult charge(ChargeCommand command);

  PaymentResult confirm(ConfirmCommand command);

  PaymentResult cancel(String paymentKey, String reason);

  default PaymentResult cancel(CancelCommand command) {
    return cancel(command.paymentKey(), command.reason());
  }

  default void deleteBillingKey(String billingKey) {}

  PaymentResult inquire(String orderId);

  /** 대사용 거래 조회다. 승인 여부와 승인액·취소 누계를 돌려준다. 금액을 알 수 없는 구현은 금액을 null로 둔다. */
  default TransactionSnapshot lookup(String orderId) {
    PaymentResult result = inquire(orderId);
    return new TransactionSnapshot(result.status(), null, null);
  }

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

  record CancelCommand(String paymentKey, String reason, Long amount, String idempotencyKey) {}

  record PaymentResult(PaymentStatus status, String paymentKey, String failureCode) {}

  record TransactionSnapshot(PaymentStatus status, Long approvedAmount, Long cancelledAmount) {}
}
