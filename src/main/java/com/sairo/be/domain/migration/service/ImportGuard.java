package com.sairo.be.domain.migration.service;

import com.sairo.be.domain.migration.entity.ImportBatch;
import com.sairo.be.domain.migration.repository.ImportBatchRepository;
import com.sairo.be.domain.migration.repository.ImportSupportLinkRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class ImportGuard {

  private final ImportBatchRepository batchRepository;
  private final ImportSupportLinkRepository supportLinkRepository;

  public ImportGuard(
      ImportBatchRepository batchRepository, ImportSupportLinkRepository supportLinkRepository) {
    this.batchRepository = batchRepository;
    this.supportLinkRepository = supportLinkRepository;
  }

  public ImportBatch find(Long officeId, Long batchId) {
    return batchRepository
        .findByIdAndOfficeId(batchId, officeId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  public ImportBatch lock(Long officeId, Long batchId) {
    return batchRepository
        .findForUpdate(batchId, officeId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  public void requireNotManagedBySupport(Long batchId) {
    if (supportLinkRepository.findActive(batchId).isPresent()) {
      throw new BusinessException(ErrorCode.IMPORT_MANAGED_BY_SUPPORT);
    }
  }

  public void requireEditable(ImportBatch batch) {
    if (!batch.getStatus().isEditable()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
  }

  public void requireVersion(ImportBatch batch, long expectedVersion) {
    if (batch.getVersion() != expectedVersion) {
      throw BusinessException.versionConflict(batch.getVersion());
    }
  }

  public void requireVersionIfPresent(ImportBatch batch, Long expectedVersion) {
    if (expectedVersion != null) {
      requireVersion(batch, expectedVersion);
    }
  }
}
