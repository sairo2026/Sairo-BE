package com.sairo.be.domain.billing.repository;

import com.sairo.be.domain.billing.entity.BillingChargeAttempt;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingChargeAttemptRepository extends JpaRepository<BillingChargeAttempt, Long> {

  List<BillingChargeAttempt> findByInvoiceIdOrderByAttemptNoAsc(Long invoiceId);

  Optional<BillingChargeAttempt> findFirstByInvoiceIdOrderByAttemptNoDesc(Long invoiceId);
}
