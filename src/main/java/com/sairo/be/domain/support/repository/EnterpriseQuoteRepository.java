package com.sairo.be.domain.support.repository;

import com.sairo.be.domain.support.entity.EnterpriseQuote;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EnterpriseQuoteRepository extends JpaRepository<EnterpriseQuote, Long> {

  Optional<EnterpriseQuote> findFirstByLeadIdOrderByVersionNoDesc(Long leadId);

  Optional<EnterpriseQuote> findByIdAndLeadId(Long id, Long leadId);

  List<EnterpriseQuote> findByLeadIdOrderByVersionNoAsc(Long leadId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select q from EnterpriseQuote q where q.id = :id and q.leadId = :leadId")
  Optional<EnterpriseQuote> findForUpdate(@Param("id") Long id, @Param("leadId") Long leadId);
}
