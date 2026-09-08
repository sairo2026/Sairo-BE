package com.sairo.be.domain.coordination.repository;

import com.sairo.be.domain.coordination.entity.Coordination;
import com.sairo.be.domain.coordination.entity.CoordinationStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CoordinationRepository extends JpaRepository<Coordination, Long> {

  List<Coordination> findByOfficeIdOrderByCreatedAtDesc(Long officeId);

  Optional<Coordination> findByIdAndOfficeId(Long id, Long officeId);

  long countByOfficeIdAndStatus(Long officeId, CoordinationStatus status);

  long countByOfficeIdAndStatusAndScheduledAtGreaterThanEqualAndScheduledAtLessThan(
      Long officeId, CoordinationStatus status, Instant startInclusive, Instant endExclusive);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from Coordination c where c.id = :id")
  Optional<Coordination> findByIdForUpdate(@Param("id") Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from Coordination c where c.id = :id and c.officeId = :officeId")
  Optional<Coordination> findByIdAndOfficeIdForUpdate(
      @Param("id") Long id, @Param("officeId") Long officeId);
}
