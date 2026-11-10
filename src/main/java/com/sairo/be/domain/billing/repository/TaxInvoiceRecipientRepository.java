package com.sairo.be.domain.billing.repository;

import com.sairo.be.domain.billing.entity.RecipientStatus;
import com.sairo.be.domain.billing.entity.TaxInvoiceRecipient;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaxInvoiceRecipientRepository extends JpaRepository<TaxInvoiceRecipient, Long> {

  Optional<TaxInvoiceRecipient> findByOfficeIdAndStatus(Long officeId, RecipientStatus status);
}
