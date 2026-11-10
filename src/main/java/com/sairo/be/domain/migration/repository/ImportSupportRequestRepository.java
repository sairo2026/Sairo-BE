package com.sairo.be.domain.migration.repository;

import com.sairo.be.domain.migration.entity.ImportSupportRequest;
import com.sairo.be.domain.migration.entity.ImportSupportStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImportSupportRequestRepository extends JpaRepository<ImportSupportRequest, Long> {

  Optional<ImportSupportRequest> findByIdAndOfficeId(Long id, Long officeId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from ImportSupportRequest r where r.id = :id and r.officeId = :officeId")
  Optional<ImportSupportRequest> findForUpdate(
      @Param("id") Long id, @Param("officeId") Long officeId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from ImportSupportRequest r where r.id = :id")
  Optional<ImportSupportRequest> findForUpdateById(@Param("id") Long id);

  @Query(
      "select r from ImportSupportRequest r where r.officeId = :officeId"
          + " and r.status in :statuses")
  Page<ImportSupportRequest> findByOffice(
      @Param("officeId") Long officeId,
      @Param("statuses") Collection<ImportSupportStatus> statuses,
      Pageable pageable);
}
