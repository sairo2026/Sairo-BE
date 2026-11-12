package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.entity.InvoiceStatus;
import com.sairo.be.domain.billing.entity.RefundReasonCode;
import com.sairo.be.domain.billing.entity.RefundRequest;
import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.mapper.BillingMapper;
import com.sairo.be.domain.billing.repository.BillingInvoiceRepository;
import com.sairo.be.domain.billing.repository.RefundRequestRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 즉시 종료(인증 중단, 사무소 즉시 종료)의 잔여기간 비례 환불 접수다. 지금 시각을 덮는 결제 성공 청구서 하나에 대해 초 단위로 계산하고 원 단위로 내린다. 같은
 * 청구서·같은 사유의 접수가 이미 있으면 다시 만들지 않는다.
 */
@Profile("!migrate")
@Component
public class RemainingPeriodRefunds {

  private final BillingInvoiceRepository invoiceRepository;
  private final RefundRequestRepository refundRepository;

  public RemainingPeriodRefunds(
      BillingInvoiceRepository invoiceRepository, RefundRequestRepository refundRepository) {
    this.invoiceRepository = invoiceRepository;
    this.refundRepository = refundRepository;
  }

  public void request(Subscription subscription, Instant now, RefundReasonCode reason) {
    invoiceRepository
        .findBySubscriptionIdAndStatusIn(
            subscription.getId(), List.of(InvoiceStatus.PAID, InvoiceStatus.PARTIALLY_REFUNDED))
        .stream()
        .filter(invoice -> invoice.getBillingPeriodStart() != null)
        .filter(invoice -> invoice.getBillingPeriodEnd().isAfter(now))
        .filter(invoice -> !invoice.getBillingPeriodStart().isAfter(now))
        .max((first, second) -> first.getBillingPeriodEnd().compareTo(second.getBillingPeriodEnd()))
        .ifPresent(
            invoice -> {
              if (refundRepository
                  .findByInvoiceIdAndReasonCode(invoice.getId(), reason)
                  .isPresent()) {
                return;
              }
              long total =
                  Duration.between(invoice.getBillingPeriodStart(), invoice.getBillingPeriodEnd())
                      .toSeconds();
              long remaining =
                  Math.max(0, Duration.between(now, invoice.getBillingPeriodEnd()).toSeconds());
              long entitlement = Math.floorDiv(invoice.getAmount() * remaining, total);
              long completed = refundRepository.sumCompleted(invoice.getId());
              long reserved = refundRepository.sumReserved(invoice.getId());
              long previousSameEvent =
                  refundRepository.sumCompletedByEventType(invoice.getId(), reason.eventType());
              long refundable = BillingMapper.refundableAmount(invoice, completed, reserved);
              long refundAmount =
                  Math.max(0, Math.min(entitlement - previousSameEvent, refundable));
              RefundRequest request =
                  RefundRequest.receive(invoice.getOfficeId(), invoice.getId(), reason, null, now);
              request.fixCalculation(
                  invoice.getAmount(),
                  total,
                  entitlement,
                  previousSameEvent,
                  reserved,
                  refundAmount);
              request.recordImmediateEnd(remaining, now);
              refundRepository.save(request);
            });
  }
}
