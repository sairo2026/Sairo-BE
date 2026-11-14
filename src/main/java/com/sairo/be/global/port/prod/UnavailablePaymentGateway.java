package com.sairo.be.global.port.prod;

import com.sairo.be.global.port.PaymentGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Keeps the API available before Toss credentials are issued, while failing every payment closed. */
@Profile("prod")
@ConditionalOnExpression("'${sairo.toss.secret-key:}'.trim() == ''")
@Component
public class UnavailablePaymentGateway implements PaymentGateway {

  private static final String CODE = "PAYMENT_PROVIDER_NOT_CONFIGURED";

  @Override
  public BillingKeyResult issueBillingKey(String authKey, String customerKey) {
    throw new BillingKeyIssueException(CODE);
  }

  @Override
  public PaymentResult charge(ChargeCommand command) {
    return failed();
  }

  @Override
  public PaymentResult confirm(ConfirmCommand command) {
    return failed();
  }

  @Override
  public PaymentResult cancel(String paymentKey, String reason) {
    return failed();
  }

  @Override
  public PaymentResult inquire(String orderId) {
    return failed();
  }

  private static PaymentResult failed() {
    return new PaymentResult(PaymentStatus.FAILED, null, CODE);
  }
}
