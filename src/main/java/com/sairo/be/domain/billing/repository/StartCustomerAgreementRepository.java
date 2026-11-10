package com.sairo.be.domain.billing.repository;

import com.sairo.be.domain.billing.entity.AgreementStatus;
import com.sairo.be.domain.billing.entity.StartCustomerAgreement;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StartCustomerAgreementRepository
    extends JpaRepository<StartCustomerAgreement, Long> {

  Optional<StartCustomerAgreement> findByOfficeIdAndStatus(Long officeId, AgreementStatus status);
}
