package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.entity.AttemptResult;
import com.sairo.be.domain.billing.entity.BillingChargeAttempt;
import com.sairo.be.domain.billing.entity.BillingInvoice;
import com.sairo.be.domain.billing.entity.RefundReasonCode;
import com.sairo.be.domain.billing.entity.RefundRequest;
import com.sairo.be.domain.billing.entity.RefundStatus;
import com.sairo.be.domain.billing.mapper.BillingMapper;
import com.sairo.be.domain.billing.repository.BillingChargeAttemptRepository;
import com.sairo.be.domain.billing.repository.BillingInvoiceRepository;
import com.sairo.be.domain.billing.repository.RefundRequestRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.port.PaymentGateway;
import com.sairo.be.global.port.PaymentGateway.CancelCommand;
import com.sairo.be.global.port.PaymentGateway.PaymentResult;
import com.sairo.be.global.port.PaymentGateway.PaymentStatus;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.TokenGenerator;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 운영자의 환불 검토와 PG 취소 실행이다. 청구서 단위 잠금으로 예약액과 PG 요청을 직렬화한다. PG 호출은 트랜잭션 밖에서 하고, 결과가 불확실하면 확인 중으로 두어 같은
 * 멱등키로만 다시 요청한다.
 */
@Profile("!migrate")
@Service
public class RefundProcessingService {

  static final String CANCEL_REASON = "사이로 구독 이용료 환불";

  private final RefundRequestRepository refundRepository;
  private final BillingInvoiceRepository invoiceRepository;
  private final BillingChargeAttemptRepository attemptRepository;
  private final PaymentGateway paymentGateway;
  private final TokenGenerator tokenGenerator;
  private final AdvisoryLock advisoryLock;
  private final TransactionTemplate transactionTemplate;
  private final BillingNoticeDispatcher noticeDispatcher;
  private final Clock clock;

  public RefundProcessingService(
      RefundRequestRepository refundRepository,
      BillingInvoiceRepository invoiceRepository,
      BillingChargeAttemptRepository attemptRepository,
      PaymentGateway paymentGateway,
      TokenGenerator tokenGenerator,
      AdvisoryLock advisoryLock,
      TransactionTemplate transactionTemplate,
      BillingNoticeDispatcher noticeDispatcher,
      Clock clock) {
    this.refundRepository = refundRepository;
    this.invoiceRepository = invoiceRepository;
    this.attemptRepository = attemptRepository;
    this.paymentGateway = paymentGateway;
    this.tokenGenerator = tokenGenerator;
    this.advisoryLock = advisoryLock;
    this.transactionTemplate = transactionTemplate;
    this.noticeDispatcher = noticeDispatcher;
    this.clock = clock;
  }

  /** 승인하면 권리액·예약액·환불 금액을 다시 산정해 고정한다. 환불할 금액이 없으면 409다. */
  @Transactional
  public RefundRequest review(long refundId, long operatorId, ReviewCommand command) {
    RefundRequest refund = lockedRefund(refundId);
    if (!refund.isAwaitingReview()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    if (command.amount() != null && !(command.approve() && acceptsOperatorAmount(refund))) {
      throw invalidAmount(
          FieldErrorCode.NOT_ALLOWED, "금액은 서비스 장애·승인 지연·정책 외 보상 환불을 승인할 때만 입력할 수 있습니다.");
    }
    if (!command.approve()) {
      refund.reject(operatorId, command.note(), now);
      RefundRequest rejected = refundRepository.saveAndFlush(refund);
      noticeDispatcher.refundRejected(rejected.getOfficeId(), command.note());
      return rejected;
    }
    if (refund.getReasonEventId() == null && command.reasonEventId() == null) {
      throw invalidEvent(FieldErrorCode.REQUIRED, "사유 사건 ID가 없는 신청은 승인할 때 사건 ID를 채워야 합니다.");
    }
    if (refund.getReasonEventId() != null
        && command.reasonEventId() != null
        && !refund.getReasonEventId().equals(command.reasonEventId())) {
      throw invalidEvent(FieldErrorCode.NOT_ALLOWED, "이미 정해진 사유 사건 ID는 바꿀 수 없습니다.");
    }
    if (refund.getReasonEventId() == null) {
      if (refundRepository
          .findByInvoiceIdAndReasonEventTypeAndReasonEventId(
              refund.getInvoiceId(), refund.getReasonEventType(), command.reasonEventId())
          .isPresent()) {
        throw new BusinessException(ErrorCode.INVALID_TRANSITION);
      }
      refund.fillReasonEvent(command.reasonEventId());
    }
    BillingInvoice invoice = invoiceRepository.findById(refund.getInvoiceId()).orElseThrow();
    long completed = refundRepository.sumCompleted(invoice.getId());
    long reserved = refundRepository.sumReserved(invoice.getId());
    long refundable = BillingMapper.refundableAmount(invoice, completed, reserved);
    long amount;
    if (refund.hasCalculation()) {
      amount = Math.max(0, Math.min(refund.getRefundAmount(), refundable));
      refund.adjustForApproval(reserved, amount);
    } else {
      long previous =
          refundRepository.sumCompletedByEventType(invoice.getId(), refund.getReasonEventType());
      long entitlement = entitlement(refund, invoice, now);
      amount = Math.max(0, Math.min(entitlement - previous, refundable));
      refund.fixCalculation(
          invoice.getAmount(), periodSeconds(invoice), entitlement, previous, reserved, amount);
    }
    if (amount <= 0) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    if (command.amount() != null) {
      if (command.amount() < 1 || command.amount() > amount) {
        throw invalidAmount(FieldErrorCode.OUT_OF_RANGE, "금액은 1원 이상이고 환불 가능 잔액 이하여야 합니다.");
      }
      refund.adjustForApproval(reserved, command.amount());
    }
    refund.approve(operatorId, now);
    RefundRequest approved = refundRepository.saveAndFlush(refund);
    noticeDispatcher.refundApproved(
        approved.getOfficeId(), approved.getRefundAmount(), calculationBasis(approved));
    return approved;
  }

  /** 실행 전 상태 검사다. 단계 재인증 검사보다 먼저 404를 가리기 위해 호출 쪽이 먼저 부른다. */
  @Transactional(readOnly = true)
  public RefundRequest find(long refundId) {
    return refundRepository
        .findById(refundId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  /** 승인됐거나 확인 중인 환불의 PG 취소를 요청한다. 정책 외 보상은 승인한 운영자가 실행할 수 없다. */
  public RefundRequest execute(long refundId, long operatorId) {
    Prepared prepared = transactionTemplate.execute(status -> prepare(refundId, operatorId));
    PaymentResult result;
    try {
      result = paymentGateway.cancel(prepared.command());
    } catch (RuntimeException e) {
      result = new PaymentResult(PaymentStatus.VERIFYING, null, null);
    }
    PaymentResult finalResult = result;
    return transactionTemplate.execute(status -> apply(refundId, finalResult));
  }

  private Prepared prepare(long refundId, long operatorId) {
    RefundRequest refund = lockedRefund(refundId);
    RefundStatus status = refund.getStatus();
    if (status != RefundStatus.APPROVED
        && status != RefundStatus.VERIFYING
        && status != RefundStatus.PROCESSING) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    if (refund.getReasonCode() == RefundReasonCode.POLICY_COMPENSATION
        && Long.valueOf(operatorId).equals(refund.getReviewedByOperatorId())) {
      throw new BusinessException(ErrorCode.SELF_APPROVAL_FORBIDDEN);
    }
    String paymentKey =
        attemptRepository
            .findFirstByInvoiceIdAndResultOrderByAttemptNoDesc(
                refund.getInvoiceId(), AttemptResult.SUCCESS)
            .map(BillingChargeAttempt::getPgTransactionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TRANSITION));
    refund.beginProcessing(tokenGenerator.uuid());
    refundRepository.saveAndFlush(refund);
    return new Prepared(
        new CancelCommand(
            paymentKey, CANCEL_REASON, refund.getRefundAmount(), refund.getPgIdempotencyKey()));
  }

  private RefundRequest apply(long refundId, PaymentResult result) {
    RefundRequest refund = lockedRefund(refundId);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    switch (result.status()) {
      case SUCCEEDED -> {
        refund.markCompleted(result.paymentKey(), now);
        refundRepository.saveAndFlush(refund);
        BillingInvoice invoice = invoiceRepository.findById(refund.getInvoiceId()).orElseThrow();
        invoice.applyRefunded(refundRepository.sumCompleted(invoice.getId()));
        invoiceRepository.saveAndFlush(invoice);
        noticeDispatcher.refundCompleted(refund.getOfficeId(), refund.getRefundAmount());
      }
      case FAILED -> {
        refund.markFailed(failureReason(result.failureCode()), now);
        refundRepository.saveAndFlush(refund);
        noticeDispatcher.refundFailed(refund.getOfficeId());
      }
      case VERIFYING -> {
        refund.markVerifying(result.paymentKey());
        refundRepository.saveAndFlush(refund);
      }
    }
    return refund;
  }

  private static String calculationBasis(RefundRequest refund) {
    return String.format(
        "결제 금액 %,d원, 환불 권리액 %,d원, 앞서 환불한 금액 %,d원",
        refund.getOriginalApprovedAmount(),
        refund.getEntitlementAmount(),
        refund.getPreviousRefundAmount());
  }

  private RefundRequest lockedRefund(long refundId) {
    Long invoiceId =
        refundRepository
            .findInvoiceIdById(refundId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    advisoryLock.lockForTransaction("billing:invoice:" + invoiceId);
    return refundRepository.findById(refundId).orElseThrow();
  }

  /** 해지·인증 중단은 남은 이용 기간 초 비율, 그 밖의 사유는 원승인액을 사건별 총권리액으로 둔다. */
  static long entitlement(RefundRequest refund, BillingInvoice invoice, Instant now) {
    RefundReasonCode reason = refund.getReasonCode();
    if (reason != RefundReasonCode.CANCELLATION && reason != RefundReasonCode.AUTH_SUSPENSION) {
      return invoice.getAmount();
    }
    Long total = periodSeconds(invoice);
    if (total == null) {
      return invoice.getAmount();
    }
    Instant end = refund.getEffectiveEndAt() != null ? refund.getEffectiveEndAt() : now;
    long remaining = Math.max(0, Duration.between(end, invoice.getBillingPeriodEnd()).toSeconds());
    return BigInteger.valueOf(invoice.getAmount())
        .multiply(BigInteger.valueOf(Math.min(remaining, total)))
        .divide(BigInteger.valueOf(total))
        .longValueExact();
  }

  private static Long periodSeconds(BillingInvoice invoice) {
    if (invoice.getBillingPeriodStart() == null) {
      return null;
    }
    long seconds =
        Duration.between(invoice.getBillingPeriodStart(), invoice.getBillingPeriodEnd())
            .toSeconds();
    return seconds > 0 ? seconds : null;
  }

  private static boolean acceptsOperatorAmount(RefundRequest refund) {
    RefundReasonCode reason = refund.getReasonCode();
    return reason == RefundReasonCode.SERVICE_FAILURE
        || reason == RefundReasonCode.LATE_APPROVAL
        || reason == RefundReasonCode.POLICY_COMPENSATION;
  }

  private static BusinessException invalidAmount(FieldErrorCode code, String message) {
    return BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST, List.of(new FieldErrorItem("amount", code, message)));
  }

  private static BusinessException invalidEvent(FieldErrorCode code, String message) {
    return BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST, List.of(new FieldErrorItem("reasonEventId", code, message)));
  }

  static String failureReason(String code) {
    return code == null ? "결제대행사가 취소를 처리하지 못했습니다." : "결제대행사가 취소를 거절했습니다. 코드 " + code;
  }

  public record ReviewCommand(boolean approve, String note, Long reasonEventId, Long amount) {}

  private record Prepared(CancelCommand command) {}
}
