package com.sairo.be.domain.support.repository;

import com.sairo.be.domain.support.entity.EnterpriseLead;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EnterpriseLeadRepository extends JpaRepository<EnterpriseLead, Long> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select l from EnterpriseLead l where l.id = :id")
  Optional<EnterpriseLead> findForUpdateById(@Param("id") Long id);
}
