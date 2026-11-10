package com.sairo.be.domain.migration.repository;

import com.sairo.be.domain.migration.entity.ImportMappingVersion;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImportMappingVersionRepository extends JpaRepository<ImportMappingVersion, Long> {

  Optional<ImportMappingVersion> findFirstByImportBatchIdOrderByVersionNoDesc(Long importBatchId);

  @Query(
      "select coalesce(max(v.versionNo), 0) from ImportMappingVersion v"
          + " where v.importBatchId = :batchId")
  int findMaxVersionNo(@Param("batchId") Long batchId);
}
