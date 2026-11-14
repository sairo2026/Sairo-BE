package com.sairo.be.global.port.prod;

import com.sairo.be.global.port.PaymentGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("prod")
@ConditionalOnExpression("'${sairo.toss.secret-key:}'.trim() == ''")
@Component
public class UnconfiguredPaymentGateway implements PaymentGateway {

  static final String FAILURE_CODE = "PG_NOT_CONFIGURED";

  @Override
  public BillingKeyResult issueBillingKey(String authKey, String customerKey) {
    throw new BillingKeyIssueException("결제 연동이 아직 준비되지 않았다");
  }

  @Override
  public PaymentResult charge(ChargeCommand command) {
    return new PaymentResult(PaymentStatus.FAILED, null, FAILURE_CODE);
  }

  @Override
  public PaymentResult confirm(ConfirmCommand command) {
    return new PaymentResult(PaymentStatus.FAILED, null, FAILURE_CODE);
  }

  @Override
  public PaymentResult cancel(String paymentKey, String reason) {
    return new PaymentResult(PaymentStatus.FAILED, paymentKey, FAILURE_CODE);
  }

  @Override
  public PaymentResult inquire(String orderId) {
    return new PaymentResult(PaymentStatus.FAILED, null, FAILURE_CODE);
  }
}
