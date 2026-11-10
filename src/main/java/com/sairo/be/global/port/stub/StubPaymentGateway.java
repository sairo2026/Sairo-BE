package com.sairo.be.global.port.stub;

import com.sairo.be.global.port.PaymentGateway;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!prod & !migrate")
@Component
public class StubPaymentGateway implements PaymentGateway {

  private final List<ChargeCommand> charges = new CopyOnWriteArrayList<>();
  private final AtomicReference<PaymentStatus> nextStatus =
      new AtomicReference<>(PaymentStatus.SUCCEEDED);

  public void respondWith(PaymentStatus status) {
    nextStatus.set(status);
  }

  public List<ChargeCommand> charges() {
    return List.copyOf(charges);
  }

  public void reset() {
    charges.clear();
    nextStatus.set(PaymentStatus.SUCCEEDED);
  }

  @Override
  public BillingKeyResult issueBillingKey(String authKey, String customerKey) {
    return new BillingKeyResult("stub-billing-" + customerKey, "STUB", "1234-****-****-5678");
  }

  @Override
  public PaymentResult charge(ChargeCommand command) {
    charges.add(command);
    PaymentStatus status = nextStatus.get();
    String failureCode = status == PaymentStatus.FAILED ? "STUB_DECLINED" : null;
    return new PaymentResult(status, "stub-payment-" + command.orderId(), failureCode);
  }

  @Override
  public PaymentResult cancel(String paymentKey, String reason) {
    return new PaymentResult(PaymentStatus.SUCCEEDED, paymentKey, null);
  }
}
