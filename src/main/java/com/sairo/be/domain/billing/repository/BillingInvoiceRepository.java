package com.sairo.be.domain.billing.repository;

import com.sairo.be.domain.billing.entity.BillingInvoice;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingInvoiceRepository extends JpaRepository<BillingInvoice, Long> {

  Optional<BillingInvoice> findByIdAndOfficeId(Long id, Long officeId);

  Page<BillingInvoice> findByOfficeId(Long officeId, Pageable pageable);
}
