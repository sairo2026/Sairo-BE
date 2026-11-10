package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.dto.request.RefundRequestCreateRequest;
import com.sairo.be.domain.billing.dto.response.RefundRequestCreateResponse;
import com.sairo.be.domain.billing.dto.response.RefundRequestItemResponse;
import com.sairo.be.domain.billing.dto.response.RefundRequestStatusResponse;
import com.sairo.be.domain.billing.entity.BillingInvoice;
import com.sairo.be.domain.billing.entity.RefundEventType;
import com.sairo.be.domain.billing.entity.RefundReasonCode;
import com.sairo.be.domain.billing.entity.RefundRequest;
import com.sairo.be.domain.billing.entity.RefundStatus;
import com.sairo.be.domain.billing.mapper.BillingMapper;
import com.sairo.be.domain.billing.repository.BillingInvoiceRepository;
import com.sairo.be.domain.billing.repository.RefundRequestRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import com.sairo.be.global.support.StatusFilter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class RefundRequestService {

  private static final Sort NEWEST_FIRST =
      Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.desc("id"));

  private final BillingInvoiceRepository invoiceRepository;
  private final RefundRequestRepository refundRepository;
  private final AdvisoryLock advisoryLock;
  private final Clock clock;

  public RefundRequestService(
      BillingInvoiceRepository invoiceRepository,
      RefundRequestRepository refundRepository,
      AdvisoryLock advisoryLock,
      Clock clock) {
    this.invoiceRepository = invoiceRepository;
    this.refundRepository = refundRepository;
    this.advisoryLock = advisoryLock;
    this.clock = clock;
  }

  @Transactional
  public CreateResult create(StaffPrincipal principal, RefundRequestCreateRequest request) {
    validateEventFields(request);
    Long officeId = principal.officeId();
    BillingInvoice invoice =
        invoiceRepository
            .findByIdAndOfficeId(request.invoiceId(), officeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    advisoryLock.lockForTransaction("billing:invoice:" + invoice.getId());

    Optional<RefundRequest> existing =
        request.reasonCode().isInvoiceEvent()
            ? refundRepository.findByInvoiceIdAndReasonEventTypeAndReasonEventId(
                invoice.getId(), RefundEventType.INVOICE, invoice.getId())
            : refundRepository.findByInvoiceIdAndReasonCode(invoice.getId(), request.reasonCode());
    if (existing.isPresent()) {
      return new CreateResult(toCreateResponse(existing.get()), false);
    }

    long completed = refundRepository.sumCompleted(invoice.getId());
    long reserved = refundRepository.sumReserved(invoice.getId());
    long refundable = BillingMapper.refundableAmount(invoice, completed, reserved);
    if (refundable <= 0) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }

    Instant now = clock.instant();
    RefundReasonCode reason = request.reasonCode();
    RefundRequest refund =
        RefundRequest.receive(
            officeId,
            invoice.getId(),
            reason,
            reason.isInvoiceEvent() ? invoice.getId() : null,
            now);
    if (reason.isInvoiceEvent()) {
      long entitlement = invoice.getAmount();
      long previousSameEvent =
          refundRepository.sumCompletedByEventType(invoice.getId(), RefundEventType.INVOICE);
      long refundAmount = Math.max(0, Math.min(entitlement - previousSameEvent, refundable));
      refund.fixCalculation(
          invoice.getAmount(),
          periodSeconds(invoice),
          entitlement,
          previousSameEvent,
          reserved,
          refundAmount);
    }
    RefundRequest saved = refundRepository.saveAndFlush(refund);
    return new CreateResult(toCreateResponse(saved), true);
  }

  @Transactional(readOnly = true)
  public RefundRequestStatusResponse get(StaffPrincipal principal, Long refundId) {
    return BillingMapper.toRefundStatus(
        refundRepository
            .findByIdAndOfficeId(refundId, principal.officeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)));
  }

  @Transactional(readOnly = true)
  public PageResponse<RefundRequestItemResponse> list(
      StaffPrincipal principal, Long invoiceId, String statusFilter, PageQuery page) {
    var statuses =
        StatusFilter.parse(statusFilter, RefundStatus.class, EnumSet.allOf(RefundStatus.class));
    return PageResponse.from(
        refundRepository.search(
            principal.officeId(), invoiceId, statuses, page.toPageable(NEWEST_FIRST)),
        BillingMapper::toRefundItem);
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

  private static void validateEventFields(RefundRequestCreateRequest request) {
    RefundReasonCode reason = request.reasonCode();
    boolean typeMismatch =
        request.reasonEventType() != null && request.reasonEventType() != reason.eventType();
    boolean idMismatch =
        request.reasonEventId() != null
            && !(reason.isInvoiceEvent() && request.reasonEventId().equals(request.invoiceId()));
    if (typeMismatch || idMismatch) {
      throw BusinessException.withFieldErrors(
          ErrorCode.INVALID_REQUEST,
          List.of(
              new FieldErrorItem(
                  typeMismatch ? "reasonEventType" : "reasonEventId",
                  FieldErrorCode.NOT_ALLOWED,
                  "사유 사건 정보는 사유 분류에 맞게 서버가 정한다.")));
    }
  }

  private static RefundRequestCreateResponse toCreateResponse(RefundRequest request) {
    return new RefundRequestCreateResponse(request.getId(), request.getStatus().name());
  }

  public record CreateResult(RefundRequestCreateResponse body, boolean created) {}
}
