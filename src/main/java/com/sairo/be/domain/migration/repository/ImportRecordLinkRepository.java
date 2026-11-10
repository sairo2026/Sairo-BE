package com.sairo.be.domain.migration.repository;

import com.sairo.be.domain.migration.entity.ImportRecordLink;
import com.sairo.be.domain.migration.entity.ImportTargetType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportRecordLinkRepository extends JpaRepository<ImportRecordLink, Long> {

  Optional<ImportRecordLink> findByOfficeIdAndSourceSystemAndSourceTypeAndSourceId(
      Long officeId, String sourceSystem, ImportTargetType sourceType, String sourceId);

  List<ImportRecordLink> findByOfficeIdAndSourceSystemAndSourceTypeAndSourceIdIn(
      Long officeId, String sourceSystem, ImportTargetType sourceType, Collection<String> ids);

  List<ImportRecordLink> findByBatchIdOrderByIdDesc(Long batchId);

  boolean existsByBatchId(Long batchId);
}
