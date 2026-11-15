package com.sairo.be.domain.migration.service;

import com.sairo.be.domain.migration.dto.response.ImportBatchDetailResponse;
import com.sairo.be.domain.migration.dto.response.ImportBatchListResponse;
import com.sairo.be.domain.migration.entity.ImportBatch;
import com.sairo.be.domain.migration.entity.ImportBatchStatus;
import com.sairo.be.domain.migration.entity.ImportRowOutcome;
import com.sairo.be.domain.migration.entity.ImportSkipReason;
import com.sairo.be.domain.migration.mapper.ImportBatchMapper;
import com.sairo.be.domain.migration.repository.ImportBatchJdbcRepository;
import com.sairo.be.domain.migration.repository.ImportBatchRepository;
import com.sairo.be.domain.migration.repository.ImportRowResultRepository;
import com.sairo.be.domain.migration.repository.ImportSupportLinkRepository;
import com.sairo.be.domain.migration.repository.ImportSupportLinkRepository.ActiveRequest;
import com.sairo.be.global.security.StaffPrincipal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class ImportQueryService {

  public static final int LIST_LIMIT = 100;

  private final ImportGuard guard;
  private final ImportBatchRepository batchRepository;
  private final ImportBatchJdbcRepository jdbcRepository;
  private final ImportRowResultRepository rowResultRepository;
  private final ImportSupportLinkRepository supportLinkRepository;
  private final ImportQuotaService quotaService;

  public ImportQueryService(
      ImportGuard guard,
      ImportBatchRepository batchRepository,
      ImportBatchJdbcRepository jdbcRepository,
      ImportRowResultRepository rowResultRepository,
      ImportSupportLinkRepository supportLinkRepository,
      ImportQuotaService quotaService) {
    this.guard = guard;
    this.batchRepository = batchRepository;
    this.jdbcRepository = jdbcRepository;
    this.rowResultRepository = rowResultRepository;
    this.supportLinkRepository = supportLinkRepository;
    this.quotaService = quotaService;
  }

  @Transactional(readOnly = true)
  public ImportBatchListResponse list(StaffPrincipal principal) {
    List<ImportBatch> batches =
        batchRepository.findRecent(principal.officeId(), PageRequest.of(0, LIST_LIMIT));
    Map<Long, ActiveRequest> support =
        supportLinkRepository.findActiveByBatchIds(
            batches.stream().map(ImportBatch::getId).toList());
    return new ImportBatchListResponse(
        batches.stream()
            .map(batch -> ImportBatchMapper.toListItem(batch, support.get(batch.getId())))
            .toList(),
        quotaService.remaining(principal.officeId()));
  }

  @Transactional(readOnly = true)
  public ImportBatchDetailResponse detail(StaffPrincipal principal, Long batchId) {
    ImportBatch batch = guard.find(principal.officeId(), batchId);
    ImportBatchDetailResponse.Counts counts = counts(batch);
    ImportBatchDetailResponse.CancelSummary cancelSummary = null;
    if (batch.getStatus() == ImportBatchStatus.CANCELLED && batch.getCancelledCount() != null) {
      cancelSummary =
          new ImportBatchDetailResponse.CancelSummary(
              batch.getCancelledCount(),
              ImportBatchMapper.toNotCancelled(jdbcRepository.findNotCancelled(batchId)));
    }
    return ImportBatchMapper.toDetail(
        batch, jdbcRepository.findSheets(batchId), counts, cancelSummary);
  }

  private ImportBatchDetailResponse.Counts counts(ImportBatch batch) {
    long total = batch.getRowCount() == null ? 0 : batch.getRowCount();
    if (batch.getStatus() == ImportBatchStatus.EXECUTING) {
      long succeeded =
          rowResultRepository.countByBatchIdAndResult(batch.getId(), ImportRowOutcome.SUCCEEDED);
      long failed =
          rowResultRepository.countByBatchIdAndResult(batch.getId(), ImportRowOutcome.FAILED);
      return counts(batch.getId(), total, succeeded, failed);
    }
    if (batch.getSucceededCount() == null || batch.getFailedCount() == null) {
      return null;
    }
    return counts(batch.getId(), total, batch.getSucceededCount(), batch.getFailedCount());
  }

  private ImportBatchDetailResponse.Counts counts(
      long batchId, long total, long succeeded, long failed) {
    Map<ImportSkipReason, Long> byReason = new EnumMap<>(ImportSkipReason.class);
    for (Object[] row : rowResultRepository.countSkippedByReason(batchId)) {
      byReason.put((ImportSkipReason) row[0], (Long) row[1]);
    }
    ImportBatchDetailResponse.SkippedByReason breakdown =
        new ImportBatchDetailResponse.SkippedByReason(
            byReason.getOrDefault(ImportSkipReason.ALREADY_IMPORTED, 0L),
            byReason.getOrDefault(ImportSkipReason.DUPLICATE_IN_FILE, 0L),
            byReason.getOrDefault(ImportSkipReason.DUPLICATE_EXISTING, 0L));
    long skipped =
        breakdown.alreadyImported() + breakdown.duplicateInFile() + breakdown.duplicateExisting();
    return new ImportBatchDetailResponse.Counts(
        total,
        succeeded,
        failed,
        skipped,
        Math.max(0, total - succeeded - failed - skipped),
        breakdown);
  }
}
