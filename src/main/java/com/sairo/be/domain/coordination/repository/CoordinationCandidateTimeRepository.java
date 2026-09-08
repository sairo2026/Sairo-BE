package com.sairo.be.domain.coordination.repository;

import com.sairo.be.domain.coordination.entity.CoordinationCandidateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoordinationCandidateTimeRepository
    extends JpaRepository<CoordinationCandidateTime, Long> {

  List<CoordinationCandidateTime> findByCoordinationIdOrderByStartsAtAsc(Long coordinationId);
}
