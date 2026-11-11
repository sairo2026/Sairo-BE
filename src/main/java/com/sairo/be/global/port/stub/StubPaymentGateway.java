package com.sairo.be.global.port.stub;

import com.sairo.be.global.port.PaymentGateway;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!prod & !migrate")
@ConditionalOnExpression("'${sairo.toss.secret-key:}'.trim() == ''")
@Component
public class StubPaymentGateway implements PaymentGateway {

  private final List<ChargeCommand> charges = new CopyOnWriteArrayList<>();
  private final List<ConfirmCommand> confirms = new CopyOnWriteArrayList<>();
  private final AtomicReference<PaymentStatus> nextStatus =
      new AtomicReference<>(PaymentStatus.SUCCEEDED);

  private final AtomicBoolean failNextIssue = new AtomicBoolean(false);
  private final List<String> issuedCustomerKeys = new CopyOnWriteArrayList<>();
  private final List<String> deletedBillingKeys = new CopyOnWriteArrayList<>();
  private final Map<String, PaymentResult> orders = new ConcurrentHashMap<>();
  private final AtomicReference<RuntimeException> chargeFailure = new AtomicReference<>();
  private final AtomicReference<String> failureCode = new AtomicReference<>("STUB_DECLINED");

  public void respondWith(PaymentStatus status) {
    nextStatus.set(status);
  }

  public void failNextBillingKeyIssue() {
    failNextIssue.set(true);
  }

  public void failWithCode(String code) {
    failureCode.set(code);
  }

  public void throwOnNextCharge(RuntimeException exception) {
    chargeFailure.set(exception);
  }

  public void resolve(String orderId, PaymentStatus status) {
    PaymentResult previous = orders.get(orderId);
    String paymentKey = previous == null ? "stub-payment-" + orderId : previous.paymentKey();
    orders.put(
        orderId,
        new PaymentResult(
            status, paymentKey, status == PaymentStatus.FAILED ? "STUB_DECLINED" : null));
  }

  public List<String> issuedCustomerKeys() {
    return List.copyOf(issuedCustomerKeys);
  }

  public List<ConfirmCommand> confirms() {
    return List.copyOf(confirms);
  }

  public List<String> deletedBillingKeys() {
    return List.copyOf(deletedBillingKeys);
  }

  public List<ChargeCommand> charges() {
    return List.copyOf(charges);
  }

  public void reset() {
    charges.clear();
    confirms.clear();
    issuedCustomerKeys.clear();
    deletedBillingKeys.clear();
    orders.clear();
    chargeFailure.set(null);
    failureCode.set("STUB_DECLINED");
    failNextIssue.set(false);
    nextStatus.set(PaymentStatus.SUCCEEDED);
  }

  @Override
  public BillingKeyResult issueBillingKey(String authKey, String customerKey) {
    if (failNextIssue.getAndSet(false)) {
      throw new BillingKeyIssueException("stub issue failure");
    }
    issuedCustomerKeys.add(customerKey);
    return new BillingKeyResult("stub-billing-" + customerKey, "STUB", "1234-****-****-5678");
  }

  @Override
  public void deleteBillingKey(String billingKey) {
    deletedBillingKeys.add(billingKey);
  }

  @Override
  public PaymentResult charge(ChargeCommand command) {
    charges.add(command);
    RuntimeException failure = chargeFailure.getAndSet(null);
    if (failure != null) {
      orders.put(
          command.orderId(),
          new PaymentResult(PaymentStatus.VERIFYING, "stub-payment-" + command.orderId(), null));
      throw failure;
    }
    PaymentStatus status = nextStatus.get();
    String code = status == PaymentStatus.FAILED ? failureCode.get() : null;
    PaymentResult result = new PaymentResult(status, "stub-payment-" + command.orderId(), code);
    orders.put(command.orderId(), result);
    return result;
  }

  @Override
  public PaymentResult confirm(ConfirmCommand command) {
    confirms.add(command);
    RuntimeException failure = chargeFailure.getAndSet(null);
    if (failure != null) {
      orders.put(
          command.orderId(),
          new PaymentResult(PaymentStatus.VERIFYING, command.paymentKey(), null));
      throw failure;
    }
    PaymentStatus status = nextStatus.get();
    String code = status == PaymentStatus.FAILED ? failureCode.get() : null;
    PaymentResult result = new PaymentResult(status, command.paymentKey(), code);
    orders.put(command.orderId(), result);
    return result;
  }

  @Override
  public PaymentResult inquire(String orderId) {
    PaymentResult found = orders.get(orderId);
    return found == null ? new PaymentResult(PaymentStatus.FAILED, null, "NOT_FOUND") : found;
  }

  @Override
  public PaymentResult cancel(String paymentKey, String reason) {
    return new PaymentResult(PaymentStatus.SUCCEEDED, paymentKey, null);
  }
}
