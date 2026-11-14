package com.sairo.be.domain.legal.repository;

import com.sairo.be.domain.legal.entity.PrivacyRequest;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PrivacyRequestRepository extends JpaRepository<PrivacyRequest, Long> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from PrivacyRequest r where r.id = :id")
  Optional<PrivacyRequest> findForUpdateById(@Param("id") Long id);
}
