package com.sairo.be.domain.billing.repository;

import com.sairo.be.domain.billing.entity.RefundEventType;
import com.sairo.be.domain.billing.entity.RefundReasonCode;
import com.sairo.be.domain.billing.entity.RefundRequest;
import com.sairo.be.domain.billing.entity.RefundStatus;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRequestRepository extends JpaRepository<RefundRequest, Long> {

  Optional<RefundRequest> findByIdAndOfficeId(Long id, Long officeId);

  Optional<RefundRequest> findByInvoiceIdAndReasonCode(Long invoiceId, RefundReasonCode reasonCode);

  Optional<RefundRequest> findByInvoiceIdAndReasonEventTypeAndReasonEventId(
      Long invoiceId, RefundEventType reasonEventType, Long reasonEventId);

  @Query(
      """
      select r from RefundRequest r
      where r.officeId = :officeId
        and (:invoiceId is null or r.invoiceId = :invoiceId)
        and r.status in :statuses
      """)
  Page<RefundRequest> search(
      @Param("officeId") Long officeId,
      @Param("invoiceId") Long invoiceId,
      @Param("statuses") Collection<RefundStatus> statuses,
      Pageable pageable);

  @Query(
      """
      select coalesce(sum(r.refundAmount), 0) from RefundRequest r
      where r.invoiceId = :invoiceId and r.reasonEventType = :eventType
        and r.status = com.sairo.be.domain.billing.entity.RefundStatus.COMPLETED
      """)
  long sumCompletedByEventType(
      @Param("invoiceId") Long invoiceId, @Param("eventType") RefundEventType eventType);

  @Query(
      """
      select coalesce(sum(r.refundAmount), 0) from RefundRequest r
      where r.invoiceId = :invoiceId
        and r.status = com.sairo.be.domain.billing.entity.RefundStatus.COMPLETED
      """)
  long sumCompleted(@Param("invoiceId") Long invoiceId);

  @Query(
      """
      select coalesce(sum(r.refundAmount), 0) from RefundRequest r
      where r.invoiceId = :invoiceId
        and r.status in (com.sairo.be.domain.billing.entity.RefundStatus.APPROVED,
                         com.sairo.be.domain.billing.entity.RefundStatus.PROCESSING,
                         com.sairo.be.domain.billing.entity.RefundStatus.VERIFYING)
      """)
  long sumReserved(@Param("invoiceId") Long invoiceId);
}
