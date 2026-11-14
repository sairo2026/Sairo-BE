package com.sairo.be.domain.customer.repository;

import com.sairo.be.domain.customer.entity.CustomerPropertyInterest;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerPropertyInterestRepository
    extends JpaRepository<CustomerPropertyInterest, Long> {

  Optional<CustomerPropertyInterest> findByIdAndOfficeId(Long id, Long officeId);
}
