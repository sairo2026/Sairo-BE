package com.sairo.be.domain.customer.repository;

import com.sairo.be.domain.customer.entity.Customer;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

  Optional<Customer> findByIdAndOfficeId(Long id, Long officeId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from Customer c where c.id = :id and c.officeId = :officeId")
  Optional<Customer> findForUpdate(@Param("id") Long id, @Param("officeId") Long officeId);
}
