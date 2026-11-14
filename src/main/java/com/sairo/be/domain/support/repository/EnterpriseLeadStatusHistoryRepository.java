package com.sairo.be.domain.support.repository;

import com.sairo.be.domain.support.entity.EnterpriseLeadStatusHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnterpriseLeadStatusHistoryRepository
    extends JpaRepository<EnterpriseLeadStatusHistory, Long> {

  List<EnterpriseLeadStatusHistory> findByLeadIdOrderByCreatedAtAscIdAsc(Long leadId);
}
