package com.sairo.be.domain.migration.service;

import com.sairo.be.domain.migration.dto.request.ImportMappingConfirmRequest;
import com.sairo.be.domain.migration.dto.response.ImportExecuteResponse;
import com.sairo.be.domain.migration.dto.response.ImportMappingConfirmResponse;
import com.sairo.be.domain.migration.entity.ImportBatch;
import com.sairo.be.domain.migration.entity.ImportBatchStatus;
import com.sairo.be.domain.migration.entity.ImportMappingVersion;
import com.sairo.be.domain.migration.entity.ImportSupportRequest;
import com.sairo.be.domain.migration.entity.ImportSupportStatus;
import com.sairo.be.domain.migration.mapper.ImportBatchMapper;
import com.sairo.be.domain.migration.repository.ImportBatchRepository;
import com.sairo.be.domain.migration.repository.ImportMappingVersionRepository;
import com.sairo.be.domain.migration.repository.ImportSupportJdbcRepository;
import com.sairo.be.domain.migration.repository.ImportSupportRequestRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.AfterCommit;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class ImportSupportOperatorService {

  public record Transition(
      Long requestId, ImportSupportStatus status, Long version, Instant updatedAt) {}

  private final ImportSupportRequestRepository requestRepository;
  private final ImportSupportJdbcRepository jdbcRepository;
  private final ImportBatchRepository batchRepository;
  private final ImportMappingVersionRepository versionRepository;
  private final ImportMappingService mappingService;
  private final ImportRunService runService;
  private final ImportJobExecutor jobExecutor;
  private final Clock clock;

  public ImportSupportOperatorService(
      ImportSupportRequestRepository requestRepository,
      ImportSupportJdbcRepository jdbcRepository,
      ImportBatchRepository batchRepository,
      ImportMappingVersionRepository versionRepository,
      ImportMappingService mappingService,
      ImportRunService runService,
      ImportJobExecutor jobExecutor,
      Clock clock) {
    this.requestRepository = requestRepository;
    this.jdbcRepository = jdbcRepository;
    this.batchRepository = batchRepository;
    this.versionRepository = versionRepository;
    this.mappingService = mappingService;
    this.runService = runService;
    this.jobExecutor = jobExecutor;
    this.clock = clock;
  }

  @Transactional
  public Transition quote(Long requestId, long quoteAmount, String scopeNote) {
    if (quoteAmount <= 0) {
      throw ImportRequestParser.invalid(
          "quoteAmount", FieldErrorCode.OUT_OF_RANGE, "견적 금액은 0보다 커야 합니다.");
    }
    ImportSupportRequest request = lock(requestId);
    request.quote(quoteAmount, scopeNote == null || scopeNote.isBlank() ? null : scopeNote.strip());
    return save(request);
  }

  @Transactional
  public Transition reject(Long requestId, Long operatorId, String reason) {
    if (reason == null || reason.isBlank()) {
      throw ImportRequestParser.invalid("reason", FieldErrorCode.REQUIRED, "거절 사유는 필수입니다.");
    }
    ImportSupportRequest request = lock(requestId);
    request.reject(operatorId, reason.strip(), now());
    return save(request);
  }

  @Transactional
  public Transition start(Long requestId, Long operatorId) {
    ImportSupportRequest request = lock(requestId);
    request.startProcessing(operatorId, now());
    return save(request);
  }

  @Transactional
  public ImportMappingConfirmResponse saveMapping(
      Long requestId, Long operatorId, ImportMappingConfirmRequest mapping) {
    ImportSupportRequest request = lock(requestId);
    requireStatus(request, ImportSupportStatus.PROCESSING);
    requireAssigned(request, operatorId);
    return mappingService.confirmByOperator(request.getImportBatchId(), operatorId, mapping);
  }

  @Transactional
  public Transition submitReview(Long requestId, Long operatorId) {
    ImportSupportRequest request = lock(requestId);
    requireStatus(request, ImportSupportStatus.PROCESSING);
    requireAssigned(request, operatorId);
    ImportMappingVersion latest =
        versionRepository
            .findFirstByImportBatchIdOrderByVersionNoDesc(request.getImportBatchId())
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TRANSITION));
    request.submitForReview(latest.getId(), now());
    return save(request);
  }

  @Transactional
  public ImportExecuteResponse execute(Long requestId, Long operatorId) {
    ImportSupportRequest request = lock(requestId);
    requireStatus(request, ImportSupportStatus.APPROVED);
    requireAssigned(request, operatorId);
    ImportBatch batch =
        batchRepository
            .findForUpdateById(request.getImportBatchId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (batch.getStatus() != ImportBatchStatus.MAPPING
        && batch.getStatus() != ImportBatchStatus.PREVIEW) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    StaffPrincipal owner =
        jdbcRepository
            .findOwnerPrincipal(request.getApprovedByMembershipId(), request.getOfficeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TRANSITION));
    batch.startExecution();
    ImportBatch saved = batchRepository.saveAndFlush(batch);
    long batchId = saved.getId();
    Long mappingVersionId = request.getApprovedMappingVersionId();
    AfterCommit.run(
        () -> jobExecutor.submit(() -> runService.run(batchId, owner, false, mappingVersionId)));
    return ImportBatchMapper.toExecute(saved);
  }

  @Transactional
  public Transition deliver(Long requestId, Long operatorId) {
    ImportSupportRequest request = lock(requestId);
    requireStatus(request, ImportSupportStatus.APPROVED);
    requireAssigned(request, operatorId);
    ImportBatch batch =
        batchRepository
            .findById(request.getImportBatchId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (batch.getStatus() != ImportBatchStatus.COMPLETED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    request.deliver(now());
    return save(request);
  }

  private ImportSupportRequest lock(Long requestId) {
    return requestRepository
        .findForUpdateById(requestId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  private Transition save(ImportSupportRequest request) {
    ImportSupportRequest saved = requestRepository.saveAndFlush(request);
    return new Transition(
        saved.getId(), saved.getStatus(), saved.getVersion(), saved.getUpdatedAt());
  }

  private static void requireStatus(ImportSupportRequest request, ImportSupportStatus status) {
    if (request.getStatus() != status) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
  }

  private static void requireAssigned(ImportSupportRequest request, Long operatorId) {
    if (!operatorId.equals(request.getAssignedOperatorId())) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
