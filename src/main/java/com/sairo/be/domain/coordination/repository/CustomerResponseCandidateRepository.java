package com.sairo.be.domain.coordination.repository;

import com.sairo.be.domain.coordination.entity.CustomerResponseCandidate;
import com.sairo.be.domain.coordination.entity.CustomerResponseCandidateId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerResponseCandidateRepository
    extends JpaRepository<CustomerResponseCandidate, CustomerResponseCandidateId> {

  List<CustomerResponseCandidate> findById_ResponseId(Long responseId);
}
