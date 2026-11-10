package com.sairo.be.domain.migration.service;

import com.sairo.be.domain.customer.service.CustomerImportService;
import com.sairo.be.domain.migration.entity.ImportBatch;
import com.sairo.be.domain.migration.entity.ImportRecordLink;
import com.sairo.be.domain.migration.entity.ImportRowOutcome;
import com.sairo.be.domain.migration.entity.ImportRowResult;
import com.sairo.be.domain.migration.entity.ImportTargetType;
import com.sairo.be.domain.migration.repository.ImportBatchRepository;
import com.sairo.be.domain.migration.repository.ImportRecordLinkRepository;
import com.sairo.be.domain.migration.repository.ImportRowResultRepository;
import com.sairo.be.domain.property.service.PropertyImportService;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Component
public class ImportRunTransactions {

  public enum RowDisposition {
    CREATED,
    LINKED
  }

  private final ImportBatchRepository batchRepository;
  private final ImportRecordLinkRepository linkRepository;
  private final ImportRowResultRepository rowResultRepository;
  private final CustomerImportService customerImportService;
  private final PropertyImportService propertyImportService;
  private final ImportRecordBodyBuilder bodyBuilder;
  private final Clock clock;

  public ImportRunTransactions(
      ImportBatchRepository batchRepository,
      ImportRecordLinkRepository linkRepository,
      ImportRowResultRepository rowResultRepository,
      CustomerImportService customerImportService,
      PropertyImportService propertyImportService,
      ImportRecordBodyBuilder bodyBuilder,
      Clock clock) {
    this.batchRepository = batchRepository;
    this.linkRepository = linkRepository;
    this.rowResultRepository = rowResultRepository;
    this.customerImportService = customerImportService;
    this.propertyImportService = propertyImportService;
    this.bodyBuilder = bodyBuilder;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public RowDisposition writeRow(
      StaffPrincipal principal,
      ImportBatch batch,
      String sourceSystem,
      NormalizedRow normalized,
      int rowNumber) {
    ImportTargetType type = batch.getTargetType();
    String sourceId = normalized.sourceId();
    Optional<ImportRecordLink> existing =
        linkRepository.findByOfficeIdAndSourceSystemAndSourceTypeAndSourceId(
            batch.getOfficeId(), sourceSystem, type, sourceId);
    RowDisposition disposition;
    if (existing.isPresent()) {
      disposition = RowDisposition.LINKED;
    } else {
      long targetId = create(principal, type, normalized);
      linkRepository.save(
          ImportRecordLink.of(
              batch.getId(), batch.getOfficeId(), sourceSystem, type, sourceId, targetId));
      disposition = RowDisposition.CREATED;
    }
    saveResult(batch, rowNumber, ImportRowOutcome.SUCCEEDED, null);
    return disposition;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordFailure(ImportBatch batch, int rowNumber, String reason) {
    saveResult(batch, rowNumber, ImportRowOutcome.FAILED, reason);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finish(long batchId) {
    ImportBatch batch = lock(batchId);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    batch.finishExecution(
        (int) rowResultRepository.countByBatchIdAndResult(batchId, ImportRowOutcome.SUCCEEDED),
        (int) rowResultRepository.countByBatchIdAndResult(batchId, ImportRowOutcome.FAILED),
        now,
        linkRepository.existsByBatchId(batchId));
    batchRepository.saveAndFlush(batch);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markFailed(long batchId) {
    ImportBatch batch = lock(batchId);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    batch.failExecution(
        (int) rowResultRepository.countByBatchIdAndResult(batchId, ImportRowOutcome.SUCCEEDED),
        (int) rowResultRepository.countByBatchIdAndResult(batchId, ImportRowOutcome.FAILED),
        now,
        linkRepository.existsByBatchId(batchId));
    batchRepository.saveAndFlush(batch);
  }

  private ImportBatch lock(long batchId) {
    return batchRepository
        .findForUpdateById(batchId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  private long create(StaffPrincipal principal, ImportTargetType type, NormalizedRow normalized) {
    return switch (type) {
      case CUSTOMER -> {
        LocalDate retention =
            TimeRules.displayDate(clock.instant())
                .plusYears(ImportRecordBodyBuilder.RETENTION_YEARS);
        yield customerImportService.create(
            principal, bodyBuilder.customer(principal, normalized.values(), retention));
      }
      case PROPERTY ->
          propertyImportService.create(
              principal, bodyBuilder.property(principal, normalized.values()));
      case CONTRACT -> throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    };
  }

  private void saveResult(
      ImportBatch batch, int rowNumber, ImportRowOutcome outcome, String reason) {
    ImportRowResult row =
        rowResultRepository.findByBatchIdAndSourceRowNumber(batch.getId(), rowNumber).orElse(null);
    if (row == null) {
      rowResultRepository.save(
          outcome == ImportRowOutcome.SUCCEEDED
              ? ImportRowResult.succeeded(batch.getId(), batch.getOfficeId(), rowNumber)
              : ImportRowResult.failed(batch.getId(), batch.getOfficeId(), rowNumber, reason));
      return;
    }
    if (outcome == ImportRowOutcome.SUCCEEDED) {
      row.markSucceeded();
    } else {
      row.markFailed(reason);
    }
    rowResultRepository.save(row);
  }
}
