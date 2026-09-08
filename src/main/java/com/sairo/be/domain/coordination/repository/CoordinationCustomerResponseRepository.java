package com.sairo.be.domain.coordination.repository;

import com.sairo.be.domain.coordination.entity.CoordinationCustomerResponse;
import com.sairo.be.domain.coordination.entity.CustomerResponseRole;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CoordinationCustomerResponseRepository
    extends JpaRepository<CoordinationCustomerResponse, Long> {

  @Query("select r.coordinationId from CoordinationCustomerResponse r where r.id = :id")
  Optional<Long> findCoordinationIdById(@Param("id") Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from CoordinationCustomerResponse r where r.id = :id")
  Optional<CoordinationCustomerResponse> findByIdForUpdate(@Param("id") Long id);

  Optional<CoordinationCustomerResponse> findByCoordinationIdAndRole(
      Long coordinationId, CustomerResponseRole role);

  boolean existsByCoordinationIdAndRole(Long coordinationId, CustomerResponseRole role);
}
