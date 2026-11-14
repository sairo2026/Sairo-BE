package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.dto.response.BillingInvoiceDetailResponse;
import com.sairo.be.domain.billing.dto.response.BillingInvoiceItemResponse;
import com.sairo.be.domain.billing.entity.BillingInvoice;
import com.sairo.be.domain.billing.mapper.BillingMapper;
import com.sairo.be.domain.billing.repository.BillingChargeAttemptRepository;
import com.sairo.be.domain.billing.repository.BillingInvoiceRepository;
import com.sairo.be.domain.billing.repository.BillingQueryRepository;
import com.sairo.be.domain.billing.repository.BillingQueryRepository.RefundSums;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
@Transactional(readOnly = true)
public class InvoiceQueryService {

  private static final Sort NEWEST_FIRST =
      Sort.by(Sort.Order.desc("billingPeriodEnd"), Sort.Order.desc("id"));

  private final BillingInvoiceRepository invoiceRepository;
  private final BillingChargeAttemptRepository attemptRepository;
  private final BillingQueryRepository queryRepository;

  public InvoiceQueryService(
      BillingInvoiceRepository invoiceRepository,
      BillingChargeAttemptRepository attemptRepository,
      BillingQueryRepository queryRepository) {
    this.invoiceRepository = invoiceRepository;
    this.attemptRepository = attemptRepository;
    this.queryRepository = queryRepository;
  }

  public PageResponse<BillingInvoiceItemResponse> list(StaffPrincipal principal, PageQuery page) {
    Page<BillingInvoice> found =
        invoiceRepository.findByOfficeId(principal.officeId(), page.toPageable(NEWEST_FIRST));
    Map<Long, RefundSums> sums =
        queryRepository.refundSums(found.getContent().stream().map(BillingInvoice::getId).toList());
    return PageResponse.from(
        found, invoice -> BillingMapper.toInvoiceItem(invoice, sums.get(invoice.getId())));
  }

  public BillingInvoiceDetailResponse detail(StaffPrincipal principal, Long invoiceId) {
    BillingInvoice invoice =
        invoiceRepository
            .findByIdAndOfficeId(invoiceId, principal.officeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    RefundSums sums = queryRepository.refundSums(List.of(invoiceId)).get(invoiceId);
    return BillingMapper.toInvoiceDetail(
        invoice,
        sums,
        attemptRepository.findByInvoiceIdOrderByAttemptNoAsc(invoiceId),
        invoice.getSupplierInfoVersion() == null
            ? null
            : queryRepository.findSupplier(invoice.getSupplierInfoVersion()).orElse(null),
        queryRepository.findTaxDocuments(invoiceId));
  }
}
