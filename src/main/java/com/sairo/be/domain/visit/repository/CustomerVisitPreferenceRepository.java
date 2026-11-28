package com.sairo.be.domain.visit.repository;

import com.sairo.be.domain.visit.entity.CustomerVisitPreference;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerVisitPreferenceRepository
    extends JpaRepository<CustomerVisitPreference, Long> {

  Optional<CustomerVisitPreference> findByOfficeIdAndCustomerId(Long officeId, Long customerId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT p FROM CustomerVisitPreference p WHERE p.officeId = :officeId AND p.customerId = :customerId")
  Optional<CustomerVisitPreference> findForUpdate(
      @Param("officeId") Long officeId, @Param("customerId") Long customerId);
}
