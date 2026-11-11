package com.sairo.be.domain.visit.repository;

import com.sairo.be.domain.visit.entity.VisitRequestStatusHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VisitRequestStatusHistoryRepository
    extends JpaRepository<VisitRequestStatusHistory, Long> {

  List<VisitRequestStatusHistory> findByRequestIdOrderByEffectiveAtAscIdAsc(Long requestId);
}
