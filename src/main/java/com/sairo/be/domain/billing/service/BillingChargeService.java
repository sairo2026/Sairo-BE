package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.entity.BillingChargeAttempt;
import com.sairo.be.domain.billing.entity.BillingCustomer;
import com.sairo.be.domain.billing.entity.BillingInvoice;
import com.sairo.be.domain.billing.entity.FailureCategory;
import com.sairo.be.domain.billing.entity.InvoiceStatus;
import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.repository.BillingChargeAttemptRepository;
import com.sairo.be.domain.billing.repository.BillingCustomerRepository;
import com.sairo.be.domain.billing.repository.BillingInvoiceRepository;
import com.sairo.be.domain.billing.repository.BillingQueryRepository;
import com.sairo.be.domain.billing.repository.SubscriptionRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.PaymentGateway;
import com.sairo.be.global.port.PaymentGateway.ChargeCommand;
import com.sairo.be.global.port.PaymentGateway.PaymentResult;
import com.sairo.be.global.port.PaymentGateway.PaymentStatus;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.TextCipher;
import com.sairo.be.global.support.TokenGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Profile("!migrate")
@Service
public class BillingChargeService {

  private static final String ORDER_NAME = "사이로 구독 이용료";

  private final BillingInvoiceRepository invoiceRepository;
  private final BillingChargeAttemptRepository attemptRepository;
  private final SubscriptionRepository subscriptionRepository;
  private final BillingCustomerRepository customerRepository;
  private final BillingQueryRepository queryRepository;
  private final PaymentGateway paymentGateway;
  private final TextCipher billingKeyCipher;
  private final TokenGenerator tokenGenerator;
  private final AdvisoryLock advisoryLock;
  private final SubscriptionTransitions transitions;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

  public BillingChargeService(
      BillingInvoiceRepository invoiceRepository,
      BillingChargeAttemptRepository attemptRepository,
      SubscriptionRepository subscriptionRepository,
      BillingCustomerRepository customerRepository,
      BillingQueryRepository queryRepository,
      PaymentGateway paymentGateway,
      @Qualifier("billingKeyCipher") TextCipher billingKeyCipher,
      TokenGenerator tokenGenerator,
      AdvisoryLock advisoryLock,
      SubscriptionTransitions transitions,
      TransactionTemplate transactionTemplate,
      Clock clock) {
    this.invoiceRepository = invoiceRepository;
    this.attemptRepository = attemptRepository;
    this.subscriptionRepository = subscriptionRepository;
    this.customerRepository = customerRepository;
    this.queryRepository = queryRepository;
    this.paymentGateway = paymentGateway;
    this.billingKeyCipher = billingKeyCipher;
    this.tokenGenerator = tokenGenerator;
    this.advisoryLock = advisoryLock;
    this.transitions = transitions;
    this.transactionTemplate = transactionTemplate;
    this.clock = clock;
  }

  public ChargeOutcome charge(Long invoiceId) {
    Prepared prepared = transactionTemplate.execute(status -> prepare(invoiceId));
    PaymentResult result;
    try {
      result = paymentGateway.charge(prepared.command());
    } catch (RuntimeException e) {
      result = new PaymentResult(PaymentStatus.VERIFYING, null, null);
    }
    PaymentResult finalResult = result;
    return transactionTemplate.execute(
        status -> apply(invoiceId, prepared.attemptId(), finalResult));
  }

  public Optional<ChargeOutcome> reconcile(Long invoiceId) {
    Optional<String> orderId =
        transactionTemplate.execute(
            status ->
                attemptRepository
                    .findFirstByInvoiceIdOrderByAttemptNoDesc(invoiceId)
                    .filter(BillingChargeAttempt::isInFlight)
                    .map(BillingChargeAttempt::getPgOrderId));
    if (orderId == null || orderId.isEmpty()) {
      return Optional.empty();
    }
    PaymentResult result;
    try {
      result = paymentGateway.inquire(orderId.get());
    } catch (RuntimeException e) {
      return Optional.empty();
    }
    if (result.status() == PaymentStatus.VERIFYING) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        transactionTemplate.execute(
            status -> {
              Long attemptId =
                  attemptRepository
                      .findFirstByInvoiceIdOrderByAttemptNoDesc(invoiceId)
                      .map(BillingChargeAttempt::getId)
                      .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
              return apply(invoiceId, attemptId, result);
            }));
  }

  private Prepared prepare(Long invoiceId) {
    Long subscriptionId =
        queryRepository
            .findInvoiceSubscriptionId(invoiceId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    advisoryLock.lockForTransaction("billing:subscription:" + subscriptionId);
    BillingInvoice invoice =
        invoiceRepository
            .findById(invoiceId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (invoice.getStatus() == InvoiceStatus.PROCESSING
        || invoice.getStatus() == InvoiceStatus.VERIFYING) {
      throw new BusinessException(ErrorCode.PAYMENT_VERIFYING);
    }
    if (!invoice.isUnpaid()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Optional<BillingChargeAttempt> last =
        attemptRepository.findFirstByInvoiceIdOrderByAttemptNoDesc(invoiceId);
    if (last.isPresent() && last.get().isInFlight()) {
      throw new BusinessException(ErrorCode.PAYMENT_VERIFYING);
    }
    BillingCustomer customer =
        customerRepository
            .findByOfficeId(invoice.getOfficeId())
            .filter(BillingCustomer::hasBillingKey)
            .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_METHOD_REQUIRED));
    int attemptNo = last.map(attempt -> attempt.getAttemptNo() + 1).orElse(1);
    String orderId = "SAIRO-" + invoiceId + "-" + attemptNo + "-" + tokenGenerator.randomToken(16);
    String idempotencyKey = tokenGenerator.uuid();
    BillingChargeAttempt attempt =
        attemptRepository.saveAndFlush(
            BillingChargeAttempt.begin(
                invoiceId, attemptNo, orderId, idempotencyKey, clock.instant()));
    invoice.markProcessing();
    invoiceRepository.saveAndFlush(invoice);
    return new Prepared(
        attempt.getId(),
        new ChargeCommand(
            billingKeyCipher.decrypt(customer.getBillingKeyEncrypted()),
            customer.getTossCustomerKey(),
            orderId,
            invoice.getAmount(),
            ORDER_NAME,
            idempotencyKey));
  }

  private ChargeOutcome apply(Long invoiceId, Long attemptId, PaymentResult result) {
    Long subscriptionId =
        queryRepository
            .findInvoiceSubscriptionId(invoiceId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    advisoryLock.lockForTransaction("billing:subscription:" + subscriptionId);
    BillingInvoice invoice = invoiceRepository.findById(invoiceId).orElseThrow();
    BillingChargeAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();
    Subscription subscription = subscriptionRepository.findById(subscriptionId).orElseThrow();
    Instant now = clock.instant();
    FailureCategory category = null;
    switch (result.status()) {
      case SUCCEEDED -> {
        attempt.markSuccess(result.paymentKey(), now);
        invoice.markPaid(now);
        transitions.onPaid(subscription, invoice);
      }
      case FAILED -> {
        category = FailureCategories.from(result.failureCode());
        attempt.markFailed(category, result.failureCode(), now);
        invoice.markFailed(null);
        transitions.onFailed(subscription, invoice, now);
        if (transitions.isPastDue(subscription)) {
          invoice.markFailed(
              FailureCategories.nextRetryAt(invoice.getDueAt(), attempt.getAttemptNo(), category));
        }
      }
      case VERIFYING -> {
        attempt.markVerifying(result.paymentKey());
        invoice.markVerifying();
      }
    }
    subscription.markEventRecorded(now);
    return new ChargeOutcome(invoice.getStatus(), category, invoiceId);
  }

  private record Prepared(Long attemptId, ChargeCommand command) {}

  public record ChargeOutcome(
      InvoiceStatus invoiceStatus, FailureCategory category, Long invoiceId) {

    public boolean isPaid() {
      return invoiceStatus == InvoiceStatus.PAID;
    }

    public boolean isVerifying() {
      return invoiceStatus == InvoiceStatus.VERIFYING;
    }
  }
}
