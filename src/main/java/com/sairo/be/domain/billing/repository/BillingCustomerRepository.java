package com.sairo.be.domain.billing.repository;

import com.sairo.be.domain.billing.entity.BillingCustomer;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingCustomerRepository extends JpaRepository<BillingCustomer, Long> {

  Optional<BillingCustomer> findByOfficeId(Long officeId);
}
