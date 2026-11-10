package com.sairo.be.domain.migration.repository;

import com.sairo.be.domain.migration.entity.ImportRowOutcome;
import com.sairo.be.domain.migration.entity.ImportRowResult;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImportRowResultRepository extends JpaRepository<ImportRowResult, Long> {

  Optional<ImportRowResult> findByBatchIdAndSourceRowNumber(Long batchId, Integer sourceRowNumber);

  long countByBatchIdAndResult(Long batchId, ImportRowOutcome result);

  @Query(
      "select r.sourceRowNumber from ImportRowResult r"
          + " where r.batchId = :batchId and r.result = 'SUCCEEDED'")
  List<Integer> findSucceededRowNumbers(@Param("batchId") Long batchId);

  @Query(
      "select r from ImportRowResult r where r.batchId = :batchId and r.result = 'FAILED'"
          + " order by r.sourceRowNumber")
  List<ImportRowResult> findFailed(@Param("batchId") Long batchId);
}
