package com.sairo.be.domain.visit.repository;

import com.sairo.be.domain.visit.entity.VisitCoordinationStatusHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VisitCoordinationStatusHistoryRepository
    extends JpaRepository<VisitCoordinationStatusHistory, Long> {

  List<VisitCoordinationStatusHistory> findByCoordinationIdOrderByIdAsc(Long coordinationId);
}
