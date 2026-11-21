package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.entity.AttemptResult;
import com.sairo.be.domain.billing.entity.BillingChargeAttempt;
import com.sairo.be.domain.billing.entity.BillingCustomer;
import com.sairo.be.domain.billing.entity.BillingInvoice;
import com.sairo.be.domain.billing.entity.FailureCategory;
import com.sairo.be.domain.billing.entity.InvoiceStatus;
import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.entity.SubscriptionStatus;
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
import java.time.Duration;
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

  /** 토스 승인 요청 제한 시간(65초)을 넘겨도 결과를 반영하지 못한 시도는 프로세스가 중단된 것으로 본다. */
  static final Duration STALE_PROCESSING_AFTER = Duration.ofMinutes(15);

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
  private final BillingNoticeDispatcher noticeDispatcher;
  private final LiveChargingGuard liveChargingGuard;
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
      BillingNoticeDispatcher noticeDispatcher,
      LiveChargingGuard liveChargingGuard,
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
    this.noticeDispatcher = noticeDispatcher;
    this.liveChargingGuard = liveChargingGuard;
    this.clock = clock;
  }

  public ChargeOutcome charge(Long invoiceId) {
    liveChargingGuard.requireChargeAllowed();
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

  /**
   * 청구서를 처리 중으로 저장한 뒤 토스 호출이나 결과 반영 전에 프로세스가 중단되면 청구서가 처리 중으로 남는다. 시도에 주문 ID가 이미 저장돼 있으므로 오래된 처리 중
   * 시도를 확인 중으로 바꿔 기존 결제 결과 확인이 토스 조회로 승인·실패를 확정하게 한다. 확정되기 전에는 새 주문을 만들지 않는다.
   */
  public int recoverStaleProcessing(Instant now) {
    Instant threshold = now.minus(STALE_PROCESSING_AFTER);
    int recovered = 0;
    for (BillingInvoice invoice : invoiceRepository.findByStatus(InvoiceStatus.PROCESSING)) {
      Long invoiceId = invoice.getId();
      if (Boolean.TRUE.equals(
          transactionTemplate.execute(status -> markStaleAsVerifying(invoiceId, threshold)))) {
        recovered++;
      }
    }
    return recovered;
  }

  private boolean markStaleAsVerifying(Long invoiceId, Instant threshold) {
    Optional<Long> subscriptionId = queryRepository.findInvoiceSubscriptionId(invoiceId);
    if (subscriptionId.isEmpty()) {
      return false;
    }
    advisoryLock.lockForTransaction("billing:subscription:" + subscriptionId.get());
    BillingInvoice invoice = invoiceRepository.findById(invoiceId).orElse(null);
    if (invoice == null || invoice.getStatus() != InvoiceStatus.PROCESSING) {
      return false;
    }
    BillingChargeAttempt attempt =
        attemptRepository.findFirstByInvoiceIdOrderByAttemptNoDesc(invoiceId).orElse(null);
    if (attempt == null
        || attempt.getResult() != AttemptResult.PROCESSING
        || attempt.getAttemptedAt().isAfter(threshold)) {
      return false;
    }
    attempt.markVerifying(null);
    invoice.markVerifying();
    return true;
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
    if (!attempt.isInFlight()) {
      // 같은 시도를 다른 작업자가 이미 확정했다. 다시 반영하면 안내와 구독 기간이 중복된다.
      return new ChargeOutcome(invoice.getStatus(), null, invoiceId);
    }
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
        boolean wasTrial = subscription.getStatus() == SubscriptionStatus.TRIAL;
        transitions.onFailed(subscription, invoice, now);
        if (wasTrial && subscription.getStatus() == SubscriptionStatus.ENDED) {
          noticeDispatcher.trialEndedPaymentFailed(
              invoice.getOfficeId(),
              BillingPeriods.returnPeriodEnd(subscription.getEndedAt()),
              FailureCategories.label(category));
        }
        if (transitions.isPastDue(subscription)) {
          invoice.markFailed(
              FailureCategories.nextRetryAt(invoice.getDueAt(), attempt.getAttemptNo(), category));
          if (attempt.getAttemptNo() == 1) {
            noticeDispatcher.paymentFailed(
                invoice.getOfficeId(), invoice.getAmount(), FailureCategories.label(category));
          }
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
