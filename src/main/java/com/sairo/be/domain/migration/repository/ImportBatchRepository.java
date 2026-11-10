package com.sairo.be.domain.migration.repository;

import com.sairo.be.domain.migration.entity.ImportBatch;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {

  Optional<ImportBatch> findByIdAndOfficeId(Long id, Long officeId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select b from ImportBatch b where b.id = :id and b.officeId = :officeId")
  Optional<ImportBatch> findForUpdate(@Param("id") Long id, @Param("officeId") Long officeId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select b from ImportBatch b where b.id = :id")
  Optional<ImportBatch> findForUpdateById(@Param("id") Long id);

  @Query("select b from ImportBatch b where b.officeId = :officeId order by b.id desc")
  List<ImportBatch> findRecent(@Param("officeId") Long officeId, Pageable pageable);
}
