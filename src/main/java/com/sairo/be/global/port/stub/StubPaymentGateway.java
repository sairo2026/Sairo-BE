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
  private final List<CancelCommand> cancels = new CopyOnWriteArrayList<>();
  private final AtomicReference<PaymentStatus> nextCancelStatus =
      new AtomicReference<>(PaymentStatus.SUCCEEDED);
  private final AtomicReference<RuntimeException> cancelFailure = new AtomicReference<>();
  private final Map<String, Long> approvedAmounts = new ConcurrentHashMap<>();
  private final Map<String, Long> cancelledAmounts = new ConcurrentHashMap<>();
  private final Map<String, String> orderByPaymentKey = new ConcurrentHashMap<>();

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

  /** 시험용 승인 거래를 남긴다. 대사 조회가 이 금액을 돌려준다. */
  public void recordApproval(String orderId, String paymentKey, long amount) {
    orders.put(orderId, new PaymentResult(PaymentStatus.SUCCEEDED, paymentKey, null));
    approvedAmounts.put(orderId, amount);
    orderByPaymentKey.put(paymentKey, orderId);
  }

  public void respondToCancelWith(PaymentStatus status) {
    nextCancelStatus.set(status);
  }

  public void throwOnNextCancel(RuntimeException exception) {
    cancelFailure.set(exception);
  }

  public List<CancelCommand> cancels() {
    return List.copyOf(cancels);
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
    cancels.clear();
    approvedAmounts.clear();
    cancelledAmounts.clear();
    orderByPaymentKey.clear();
    cancelFailure.set(null);
    nextCancelStatus.set(PaymentStatus.SUCCEEDED);
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
    if (status == PaymentStatus.SUCCEEDED) {
      approvedAmounts.put(command.orderId(), command.amount());
      orderByPaymentKey.put(result.paymentKey(), command.orderId());
    }
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
  public TransactionSnapshot lookup(String orderId) {
    PaymentResult found = orders.get(orderId);
    if (found == null) {
      return new TransactionSnapshot(PaymentStatus.FAILED, null, null);
    }
    return new TransactionSnapshot(
        found.status(),
        approvedAmounts.get(orderId),
        found.status() == PaymentStatus.SUCCEEDED
            ? cancelledAmounts.getOrDefault(orderId, 0L)
            : null);
  }

  @Override
  public PaymentResult cancel(String paymentKey, String reason) {
    return cancel(new CancelCommand(paymentKey, reason, null, null));
  }

  @Override
  public PaymentResult cancel(CancelCommand command) {
    cancels.add(command);
    RuntimeException failure = cancelFailure.getAndSet(null);
    if (failure != null) {
      throw failure;
    }
    PaymentStatus status = nextCancelStatus.get();
    if (status == PaymentStatus.SUCCEEDED && command.amount() != null) {
      String orderId = orderByPaymentKey.get(command.paymentKey());
      if (orderId != null) {
        cancelledAmounts.merge(orderId, command.amount(), Long::sum);
      }
    }
    String code = status == PaymentStatus.FAILED ? "STUB_CANCEL_DECLINED" : null;
    return new PaymentResult(status, command.paymentKey(), code);
  }
}
