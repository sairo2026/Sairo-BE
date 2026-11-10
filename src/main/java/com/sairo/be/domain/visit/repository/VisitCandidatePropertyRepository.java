package com.sairo.be.domain.visit.repository;

import com.sairo.be.domain.visit.entity.VisitCandidateProperty;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VisitCandidatePropertyRepository
    extends JpaRepository<VisitCandidateProperty, Long> {

  List<VisitCandidateProperty> findByCoordinationIdOrderBySortOrderAscIdAsc(Long coordinationId);

  List<VisitCandidateProperty> findByCoordinationIdInOrderBySortOrderAscIdAsc(
      Collection<Long> coordinationIds);

  Optional<VisitCandidateProperty> findByIdAndCoordinationId(Long id, Long coordinationId);

  long countByCoordinationId(Long coordinationId);

  boolean existsByCoordinationIdAndPropertyId(Long coordinationId, Long propertyId);
}
