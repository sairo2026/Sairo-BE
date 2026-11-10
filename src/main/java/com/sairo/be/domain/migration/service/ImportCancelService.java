package com.sairo.be.domain.migration.service;

import com.sairo.be.domain.customer.service.CustomerImportService;
import com.sairo.be.domain.migration.dto.response.ImportCancelResponse;
import com.sairo.be.domain.migration.entity.ImportBatch;
import com.sairo.be.domain.migration.entity.ImportBatchStatus;
import com.sairo.be.domain.migration.entity.ImportNotCancelledItem;
import com.sairo.be.domain.migration.entity.ImportRecordLink;
import com.sairo.be.domain.migration.entity.ImportTargetType;
import com.sairo.be.domain.migration.mapper.ImportBatchMapper;
import com.sairo.be.domain.migration.repository.ImportBatchJdbcRepository;
import com.sairo.be.domain.migration.repository.ImportRecordLinkRepository;
import com.sairo.be.domain.property.service.PropertyImportService;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.ImportedRecordState;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class ImportCancelService {

  private final ImportGuard guard;
  private final ImportRecordLinkRepository linkRepository;
  private final ImportBatchJdbcRepository jdbcRepository;
  private final CustomerImportService customerImportService;
  private final PropertyImportService propertyImportService;
  private final EntityManager entityManager;
  private final Clock clock;

  public ImportCancelService(
      ImportGuard guard,
      ImportRecordLinkRepository linkRepository,
      ImportBatchJdbcRepository jdbcRepository,
      CustomerImportService customerImportService,
      PropertyImportService propertyImportService,
      EntityManager entityManager,
      Clock clock) {
    this.guard = guard;
    this.linkRepository = linkRepository;
    this.jdbcRepository = jdbcRepository;
    this.customerImportService = customerImportService;
    this.propertyImportService = propertyImportService;
    this.entityManager = entityManager;
    this.clock = clock;
  }

  @Transactional
  public ImportCancelResponse cancel(StaffPrincipal principal, Long batchId, Long expectedVersion) {
    ImportBatch batch = guard.lock(principal.officeId(), batchId);
    guard.requireVersionIfPresent(batch, expectedVersion);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    boolean finished =
        batch.getStatus() == ImportBatchStatus.COMPLETED
            || batch.getStatus() == ImportBatchStatus.FAILED;
    if (!finished || !batch.isCancelWindowOpen(now)) {
      throw new BusinessException(ErrorCode.IMPORT_NOT_CANCELLABLE);
    }
    List<ImportRecordLink> removable = new ArrayList<>();
    List<ImportRecordLink> orphaned = new ArrayList<>();
    List<ImportNotCancelledItem> notCancelled = new ArrayList<>();
    for (ImportRecordLink link : linkRepository.findByBatchIdOrderByIdDesc(batchId)) {
      ImportedRecordState state = inspect(principal.officeId(), link);
      if (!state.exists()) {
        orphaned.add(link);
      } else if (state.isRemovable()) {
        removable.add(link);
      } else {
        notCancelled.add(
            new ImportNotCancelledItem(
                link.getTargetEntityType().name(),
                link.getTargetEntityId(),
                state.blocker().name()));
      }
    }
    if (removable.isEmpty()) {
      throw new BusinessException(ErrorCode.IMPORT_NOT_CANCELLABLE);
    }
    for (ImportRecordLink link : removable) {
      remove(link);
    }
    linkRepository.deleteAll(removable);
    linkRepository.deleteAll(orphaned);
    linkRepository.flush();
    entityManager.flush();
    jdbcRepository.markCancelled(batchId, now, removable.size(), notCancelled);
    entityManager.refresh(batch);
    return ImportBatchMapper.toCancel(batch, removable.size(), notCancelled);
  }

  private ImportedRecordState inspect(Long officeId, ImportRecordLink link) {
    return link.getTargetEntityType() == ImportTargetType.CUSTOMER
        ? customerImportService.inspect(officeId, link.getTargetEntityId())
        : propertyImportService.inspect(officeId, link.getTargetEntityId());
  }

  private void remove(ImportRecordLink link) {
    if (link.getTargetEntityType() == ImportTargetType.CUSTOMER) {
      customerImportService.remove(link.getTargetEntityId());
    } else {
      propertyImportService.remove(link.getTargetEntityId());
    }
  }
}
