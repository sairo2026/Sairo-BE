package com.sairo.be.domain.office.repository;

import com.sairo.be.domain.office.entity.ChangeRequestStatus;
import com.sairo.be.domain.office.entity.OfficeChangeRequest;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OfficeChangeRequestRepository extends JpaRepository<OfficeChangeRequest, Long> {

  Page<OfficeChangeRequest> findByOfficeIdAndStatusIn(
      Long officeId, Collection<ChangeRequestStatus> statuses, Pageable pageable);

  Page<OfficeChangeRequest> findByStatusIn(
      Collection<ChangeRequestStatus> statuses, Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from OfficeChangeRequest r where r.id = :id")
  Optional<OfficeChangeRequest> findByIdForUpdate(@Param("id") Long id);
}
