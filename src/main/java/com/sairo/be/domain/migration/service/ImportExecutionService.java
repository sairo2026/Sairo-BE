package com.sairo.be.domain.migration.service;

import com.sairo.be.domain.migration.dto.response.ImportExecuteResponse;
import com.sairo.be.domain.migration.entity.ImportBatch;
import com.sairo.be.domain.migration.entity.ImportBatchStatus;
import com.sairo.be.domain.migration.entity.ImportRowOutcome;
import com.sairo.be.domain.migration.mapper.ImportBatchMapper;
import com.sairo.be.domain.migration.repository.ImportBatchRepository;
import com.sairo.be.domain.migration.repository.ImportRowResultRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.AfterCommit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class ImportExecutionService {

  private final ImportGuard guard;
  private final ImportBatchRepository batchRepository;
  private final ImportRowResultRepository rowResultRepository;
  private final ImportQuotaService quotaService;
  private final ImportJobExecutor jobExecutor;
  private final ImportRunService runService;
  private final AdvisoryLock advisoryLock;

  public ImportExecutionService(
      ImportGuard guard,
      ImportBatchRepository batchRepository,
      ImportRowResultRepository rowResultRepository,
      ImportQuotaService quotaService,
      ImportJobExecutor jobExecutor,
      ImportRunService runService,
      AdvisoryLock advisoryLock) {
    this.guard = guard;
    this.batchRepository = batchRepository;
    this.rowResultRepository = rowResultRepository;
    this.quotaService = quotaService;
    this.jobExecutor = jobExecutor;
    this.runService = runService;
    this.advisoryLock = advisoryLock;
  }

  @Transactional
  public ImportExecuteResponse execute(
      StaffPrincipal principal, Long batchId, Long expectedVersion) {
    advisoryLock.lockForTransaction("import-quota:" + principal.officeId());
    ImportBatch batch = guard.lock(principal.officeId(), batchId);
    guard.requireNotManagedBySupport(batchId);
    if (batch.getStatus() != ImportBatchStatus.MAPPING
        && batch.getStatus() != ImportBatchStatus.PREVIEW) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    guard.requireVersionIfPresent(batch, expectedVersion);
    quotaService.requireAvailable(principal.officeId());
    batch.startExecution();
    ImportBatch saved = batchRepository.saveAndFlush(batch);
    AfterCommit.run(() -> jobExecutor.submit(() -> runService.run(batchId, principal, false)));
    return ImportBatchMapper.toExecute(saved);
  }

  @Transactional
  public ImportExecuteResponse retryFailed(
      StaffPrincipal principal, Long batchId, Long expectedVersion) {
    ImportBatch batch = guard.lock(principal.officeId(), batchId);
    guard.requireNotManagedBySupport(batchId);
    if (batch.getStatus() != ImportBatchStatus.COMPLETED
        && batch.getStatus() != ImportBatchStatus.FAILED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    guard.requireVersionIfPresent(batch, expectedVersion);
    long succeeded =
        rowResultRepository.countByBatchIdAndResult(batchId, ImportRowOutcome.SUCCEEDED);
    long total = batch.getRowCount() == null ? 0 : batch.getRowCount();
    if (total - succeeded <= 0) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    batch.startExecution();
    ImportBatch saved = batchRepository.saveAndFlush(batch);
    AfterCommit.run(() -> jobExecutor.submit(() -> runService.run(batchId, principal, true)));
    return ImportBatchMapper.toExecute(saved);
  }
}
