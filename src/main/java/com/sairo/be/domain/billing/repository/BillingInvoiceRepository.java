package com.sairo.be.domain.billing.repository;

import com.sairo.be.domain.billing.entity.BillingInvoice;
import com.sairo.be.domain.billing.entity.InvoiceStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingInvoiceRepository extends JpaRepository<BillingInvoice, Long> {

  Optional<BillingInvoice> findByIdAndOfficeId(Long id, Long officeId);

  Page<BillingInvoice> findByOfficeId(Long officeId, Pageable pageable);

  Optional<BillingInvoice> findFirstBySubscriptionIdOrderByBillingPeriodEndDescIdDesc(
      Long subscriptionId);

  List<BillingInvoice> findBySubscriptionIdAndStatusIn(
      Long subscriptionId, Collection<InvoiceStatus> statuses);

  List<BillingInvoice> findByStatusAndNextRetryAtLessThanEqual(InvoiceStatus status, Instant at);

  List<BillingInvoice> findByStatus(InvoiceStatus status);

  boolean existsBySubscriptionId(Long subscriptionId);
}
