package com.sairo.be.domain.visit.repository;

import com.sairo.be.domain.visit.entity.VisitCoordination;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VisitCoordinationRepository extends JpaRepository<VisitCoordination, Long> {

  Optional<VisitCoordination> findByIdAndOfficeId(Long id, Long officeId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from VisitCoordination c where c.id = :id and c.officeId = :officeId")
  Optional<VisitCoordination> findForUpdate(@Param("id") Long id, @Param("officeId") Long officeId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from VisitCoordination c where c.id = :id")
  Optional<VisitCoordination> findForUpdateById(@Param("id") Long id);
}
