package com.sairo.be.domain.coordination.repository;

import com.sairo.be.domain.coordination.entity.CustomerResponseCandidate;
import com.sairo.be.domain.coordination.entity.CustomerResponseCandidateId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerResponseCandidateRepository
    extends JpaRepository<CustomerResponseCandidate, CustomerResponseCandidateId> {

  List<CustomerResponseCandidate> findById_ResponseId(Long responseId);

  List<CustomerResponseCandidate> findById_ResponseIdIn(Collection<Long> responseIds);

  @Modifying
  @Query("delete from CustomerResponseCandidate c where c.id.responseId = :responseId")
  void deleteByResponseId(@Param("responseId") Long responseId);
}
