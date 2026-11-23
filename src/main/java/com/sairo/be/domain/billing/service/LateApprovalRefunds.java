package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.entity.AttemptResult;
import com.sairo.be.domain.billing.entity.BillingChargeAttempt;
import com.sairo.be.domain.billing.entity.BillingInvoice;
import com.sairo.be.domain.billing.entity.InvoiceStatus;
import com.sairo.be.domain.billing.entity.RefundEventType;
import com.sairo.be.domain.billing.entity.RefundReasonCode;
import com.sairo.be.domain.billing.entity.RefundRequest;
import com.sairo.be.domain.billing.mapper.BillingMapper;
import com.sairo.be.domain.billing.repository.BillingChargeAttemptRepository;
import com.sairo.be.domain.billing.repository.BillingInvoiceRepository;
import com.sairo.be.domain.billing.repository.BillingQueryRepository;
import com.sairo.be.domain.billing.repository.RefundRequestRepository;
import com.sairo.be.global.support.AdvisoryLock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사무소가 종료된 뒤 승인으로 확정된 결제의 전액 환불 접수다. 종료한 사무소의 구독은 되살리지 않으므로 승인된 결제는 환불로만 정리된다. 접수는 기존 환불 흐름의 승인
 * 대기(RECEIVED) 상태로만 만들고, PG 취소는 운영자가 검토·실행할 때만 일어난다. 고객 안내는 보내지 않는다. 종료로 구성원 소속이 모두 해제돼 받을 사람이 없고,
 * 결제를 시도한 대표에게 환불 안내를 따로 보내는 것은 운영자의 환불 승인 안내가 맡는다.
 *
 * <p>같은 청구서의 접수는 하나뿐이다. 사유는 LATE_APPROVAL이고 사유 사건 ID는 승인된 시도 ID다. 사건 ID가 정해져 있어 운영자는 사건 ID를 채우지 않고
 * 바로 승인할 수 있다.
 */
@Slf4j
@Profile("!migrate")
@Component
public class LateApprovalRefunds {

  static final String SWEEP_JOB = "billing-late-approval-refund";

  private final BillingInvoiceRepository invoiceRepository;
  private final BillingChargeAttemptRepository attemptRepository;
  private final RefundRequestRepository refundRepository;
  private final BillingQueryRepository queryRepository;
  private final SystemJobRunService jobRunService;
  private final AdvisoryLock advisoryLock;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

  public LateApprovalRefunds(
      BillingInvoiceRepository invoiceRepository,
      BillingChargeAttemptRepository attemptRepository,
      RefundRequestRepository refundRepository,
      BillingQueryRepository queryRepository,
      SystemJobRunService jobRunService,
      AdvisoryLock advisoryLock,
      TransactionTemplate transactionTemplate,
      Clock clock) {
    this.invoiceRepository = invoiceRepository;
    this.attemptRepository = attemptRepository;
    this.refundRepository = refundRepository;
    this.queryRepository = queryRepository;
    this.jobRunService = jobRunService;
    this.advisoryLock = advisoryLock;
    this.transactionTemplate = transactionTemplate;
    this.clock = clock;
  }

  /**
   * 승인으로 확정된 청구서의 전액 환불 접수를 한 건 만든다. 호출하는 트랜잭션이 구독 잠금을 잡고 있어야 한다. 같은 청구서·사유의 접수가 있으면 만들지 않는다. 새로
   * 만들었으면 true다.
   */
  boolean requestIfMissing(BillingInvoice invoice, Long attemptId, Instant now) {
    if (refundRepository
        .findByInvoiceIdAndReasonCode(invoice.getId(), RefundReasonCode.LATE_APPROVAL)
        .isPresent()) {
      return false;
    }
    long completed = refundRepository.sumCompleted(invoice.getId());
    long reserved = refundRepository.sumReserved(invoice.getId());
    long previousSameEvent =
        refundRepository.sumCompletedByEventType(invoice.getId(), RefundEventType.LATE_APPROVAL);
    long refundable = BillingMapper.refundableAmount(invoice, completed, reserved);
    long entitlement = invoice.getAmount();
    long refundAmount = Math.max(0, Math.min(entitlement - previousSameEvent, refundable));
    RefundRequest request =
        RefundRequest.receive(
            invoice.getOfficeId(), invoice.getId(), RefundReasonCode.LATE_APPROVAL, attemptId, now);
    request.fixCalculation(
        invoice.getAmount(),
        periodSeconds(invoice),
        entitlement,
        previousSameEvent,
        reserved,
        refundAmount);
    refundRepository.saveAndFlush(request);
    log.warn(
        "사무소 종료 뒤 승인된 결제의 전액 환불을 접수했습니다. officeId={} invoiceId={} attemptId={} amount={}",
        invoice.getOfficeId(),
        invoice.getId(),
        attemptId,
        refundAmount);
    return true;
  }

  /**
   * 종료 뒤 승인으로 결제됐는데 환불 접수가 없는 청구서를 찾아 접수를 만든다. 승인 반영 때 접수를 만들지 못한 경우와 접수 기능이 들어오기 전에 승인된 결제를 함께
   * 정리하는 보완 경로다. 청구서마다 작업 기록을 남기고 실패한 작업은 다음 주기에 같은 키로 다시 시작한다. 새로 만든 접수 수를 돌려준다.
   */
  public int sweep() {
    List<Long> invoiceIds = queryRepository.findLateApprovedInvoicesWithoutRefund();
    int created = 0;
    for (Long invoiceId : invoiceIds) {
      Long officeId =
          invoiceRepository.findById(invoiceId).map(BillingInvoice::getOfficeId).orElse(null);
      boolean[] made = new boolean[1];
      jobRunService.runOnce(
          SWEEP_JOB,
          officeId,
          "late-approval-refund:" + invoiceId,
          null,
          () -> made[0] = createFor(invoiceId));
      if (made[0]) {
        created++;
      }
    }
    return created;
  }

  private boolean createFor(Long invoiceId) {
    return Boolean.TRUE.equals(
        transactionTemplate.execute(
            status -> {
              Long subscriptionId =
                  queryRepository.findInvoiceSubscriptionId(invoiceId).orElse(null);
              if (subscriptionId == null) {
                return false;
              }
              advisoryLock.lockForTransaction("billing:subscription:" + subscriptionId);
              BillingInvoice invoice = invoiceRepository.findById(invoiceId).orElse(null);
              if (invoice == null
                  || invoice.getStatus() != InvoiceStatus.PAID
                  || !queryRepository.isOfficeClosed(invoice.getOfficeId())) {
                return false;
              }
              Long attemptId =
                  attemptRepository
                      .findFirstByInvoiceIdAndResultOrderByAttemptNoDesc(
                          invoiceId, AttemptResult.SUCCESS)
                      .map(BillingChargeAttempt::getId)
                      .orElse(null);
              if (attemptId == null) {
                return false;
              }
              return requestIfMissing(
                  invoice, attemptId, clock.instant().truncatedTo(ChronoUnit.MICROS));
            }));
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
}
