package com.sairo.be.domain.migration.service;

import com.sairo.be.domain.migration.dto.request.ImportSupportCreateRequest;
import com.sairo.be.domain.migration.dto.request.ImportSupportPaymentConfirmRequest;
import com.sairo.be.domain.migration.dto.request.ImportSupportReasonRequest;
import com.sairo.be.domain.migration.dto.response.ImportSupportApproveResponse;
import com.sairo.be.domain.migration.dto.response.ImportSupportCancelResponse;
import com.sairo.be.domain.migration.dto.response.ImportSupportChangeResponse;
import com.sairo.be.domain.migration.dto.response.ImportSupportDetailResponse;
import com.sairo.be.domain.migration.dto.response.ImportSupportListItem;
import com.sairo.be.domain.migration.dto.response.ImportSupportPaymentConfirmResponse;
import com.sairo.be.domain.migration.dto.response.ImportSupportQuoteAcceptResponse;
import com.sairo.be.domain.migration.dto.response.ImportSupportStatusResponse;
import com.sairo.be.domain.migration.entity.ImportBatch;
import com.sairo.be.domain.migration.entity.ImportBatchStatus;
import com.sairo.be.domain.migration.entity.ImportMappingVersion;
import com.sairo.be.domain.migration.entity.ImportRowOutcome;
import com.sairo.be.domain.migration.entity.ImportSupportRequest;
import com.sairo.be.domain.migration.entity.ImportSupportStatus;
import com.sairo.be.domain.migration.mapper.ImportSupportMapper;
import com.sairo.be.domain.migration.repository.ImportMappingItemRepository;
import com.sairo.be.domain.migration.repository.ImportMappingVersionRepository;
import com.sairo.be.domain.migration.repository.ImportRowResultRepository;
import com.sairo.be.domain.migration.repository.ImportSupportJdbcRepository;
import com.sairo.be.domain.migration.repository.ImportSupportLinkRepository;
import com.sairo.be.domain.migration.repository.ImportSupportRequestRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.port.PaymentGateway;
import com.sairo.be.global.port.PaymentGateway.ConfirmCommand;
import com.sairo.be.global.port.PaymentGateway.PaymentResult;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import com.sairo.be.global.support.StatusFilter;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class ImportSupportService {

  private static final int PG_KEY_MAX = 200;

  private final ImportSupportRequestRepository requestRepository;
  private final ImportSupportLinkRepository linkRepository;
  private final ImportSupportJdbcRepository jdbcRepository;
  private final ImportGuard guard;
  private final ImportMappingVersionRepository versionRepository;
  private final ImportMappingItemRepository itemRepository;
  private final ImportRowResultRepository rowResultRepository;
  private final ImportPreviewService previewService;
  private final PaymentGateway paymentGateway;
  private final Clock clock;

  public ImportSupportService(
      ImportSupportRequestRepository requestRepository,
      ImportSupportLinkRepository linkRepository,
      ImportSupportJdbcRepository jdbcRepository,
      ImportGuard guard,
      ImportMappingVersionRepository versionRepository,
      ImportMappingItemRepository itemRepository,
      ImportRowResultRepository rowResultRepository,
      ImportPreviewService previewService,
      PaymentGateway paymentGateway,
      Clock clock) {
    this.requestRepository = requestRepository;
    this.linkRepository = linkRepository;
    this.jdbcRepository = jdbcRepository;
    this.guard = guard;
    this.versionRepository = versionRepository;
    this.itemRepository = itemRepository;
    this.rowResultRepository = rowResultRepository;
    this.previewService = previewService;
    this.paymentGateway = paymentGateway;
    this.clock = clock;
  }

  @Transactional
  public ImportSupportStatusResponse create(StaffPrincipal principal, JsonNode body) {
    ImportSupportCreateRequest request = ImportSupportRequestParser.create(body);
    ImportBatch batch = guard.lock(principal.officeId(), request.importBatchId());
    if (linkRepository.findActive(batch.getId()).isPresent()) {
      throw new BusinessException(ErrorCode.IMPORT_BATCH_ALREADY_LINKED);
    }
    guard.requireEditable(batch);
    try {
      ImportSupportRequest saved =
          requestRepository.saveAndFlush(
              ImportSupportRequest.request(
                  principal.officeId(),
                  batch.getId(),
                  request.sourceSystem(),
                  request.estimatedRowCount(),
                  now()));
      return ImportSupportMapper.toStatus(saved);
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(ErrorCode.IMPORT_BATCH_ALREADY_LINKED);
    }
  }

  @Transactional(readOnly = true)
  public PageResponse<ImportSupportListItem> list(
      StaffPrincipal principal, String status, Integer page, Integer size) {
    PageQuery pageQuery = PageQuery.of(page, size);
    Set<ImportSupportStatus> statuses =
        StatusFilter.parse(
            status, ImportSupportStatus.class, EnumSet.allOf(ImportSupportStatus.class));
    Page<ImportSupportRequest> result =
        requestRepository.findByOffice(
            principal.officeId(),
            statuses,
            pageQuery.toPageable(Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.desc("id"))));
    return PageResponse.from(result, ImportSupportMapper::toListItem);
  }

  @Transactional(readOnly = true)
  public ImportSupportDetailResponse detail(StaffPrincipal principal, Long requestId) {
    ImportSupportRequest request =
        requestRepository
            .findByIdAndOfficeId(requestId, principal.officeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    ImportBatch batch = guard.find(principal.officeId(), request.getImportBatchId());
    return buildDetail(request, batch);
  }

  @Transactional
  public ImportSupportQuoteAcceptResponse acceptQuote(StaffPrincipal principal, Long requestId) {
    ImportSupportRequest request = lock(principal, requestId);
    if (request.getStatus() == ImportSupportStatus.ACCEPTED) {
      return ImportSupportMapper.toQuoteAccept(request);
    }
    request.acceptQuote("IMPORT-" + request.getId() + "-1");
    return ImportSupportMapper.toQuoteAccept(requestRepository.saveAndFlush(request));
  }

  @Transactional
  public ImportSupportPaymentConfirmResponse confirmPayment(
      StaffPrincipal principal, Long requestId, JsonNode body) {
    ImportSupportPaymentConfirmRequest command = ImportSupportRequestParser.paymentConfirm(body);
    ImportSupportRequest request = lock(principal, requestId);
    if (request.getStatus() != ImportSupportStatus.ACCEPTED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    requireSameOrder(request, command);
    PaymentResult result;
    try {
      result =
          paymentGateway.confirm(
              new ConfirmCommand(command.paymentKey(), command.orderId(), command.amount()));
    } catch (RuntimeException e) {
      throw new BusinessException(ErrorCode.PAYMENT_VERIFYING);
    }
    switch (result.status()) {
      case FAILED -> throw new BusinessException(ErrorCode.PAYMENT_FAILED);
      case VERIFYING -> throw new BusinessException(ErrorCode.PAYMENT_VERIFYING);
      case SUCCEEDED -> {}
    }
    String pgKey = result.paymentKey() == null ? command.paymentKey() : result.paymentKey();
    request.markPaid(pgKey.length() > PG_KEY_MAX ? pgKey.substring(0, PG_KEY_MAX) : pgKey, now());
    return ImportSupportMapper.toPaymentConfirm(requestRepository.saveAndFlush(request));
  }

  @Transactional
  public ImportSupportApproveResponse approve(
      StaffPrincipal principal, Long requestId, long expectedVersion) {
    ImportSupportRequest request = lock(principal, requestId);
    requireVersion(request, expectedVersion);
    request.approve(principal.membershipId(), now());
    ImportSupportRequest saved = requestRepository.saveAndFlush(request);
    int versionNo =
        versionRepository
            .findById(saved.getApprovedMappingVersionId())
            .map(ImportMappingVersion::getVersionNo)
            .orElseThrow();
    return ImportSupportMapper.toApprove(saved, versionNo);
  }

  @Transactional
  public ImportSupportChangeResponse requestChanges(
      StaffPrincipal principal, Long requestId, long expectedVersion, JsonNode body) {
    ImportSupportReasonRequest reason = ImportSupportRequestParser.requiredReason(body);
    ImportSupportRequest request = lock(principal, requestId);
    requireVersion(request, expectedVersion);
    request.requestChanges(reason.reason(), now());
    return ImportSupportMapper.toChange(requestRepository.saveAndFlush(request));
  }

  @Transactional
  public ImportSupportCancelResponse cancel(
      StaffPrincipal principal, Long requestId, long expectedVersion, JsonNode body) {
    ImportSupportReasonRequest reason = ImportSupportRequestParser.optionalReason(body);
    ImportSupportRequest request = lock(principal, requestId);
    requireVersion(request, expectedVersion);
    request.cancel(principal.membershipId(), reason.reason(), now());
    return ImportSupportMapper.toCancel(requestRepository.saveAndFlush(request));
  }

  private ImportSupportRequest lock(StaffPrincipal principal, Long requestId) {
    return requestRepository
        .findForUpdate(requestId, principal.officeId())
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  private static void requireVersion(ImportSupportRequest request, long expectedVersion) {
    if (request.getVersion() != expectedVersion) {
      throw BusinessException.versionConflict(request.getVersion());
    }
  }

  private static void requireSameOrder(
      ImportSupportRequest request, ImportSupportPaymentConfirmRequest command) {
    List<FieldErrorItem> errors = new ArrayList<>();
    if (!command.orderId().equals(request.getPaymentOrderId())) {
      errors.add(
          new FieldErrorItem("orderId", FieldErrorCode.INVALID_VALUE, "견적 수락 때 받은 주문 ID와 다릅니다."));
    }
    if (request.getPaymentAmount() == null || command.amount() != request.getPaymentAmount()) {
      errors.add(
          new FieldErrorItem("amount", FieldErrorCode.INVALID_VALUE, "견적 수락 때 정한 결제 금액과 다릅니다."));
    }
    if (!errors.isEmpty()) {
      throw BusinessException.withFieldErrors(ErrorCode.INVALID_REQUEST, errors);
    }
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private ImportSupportDetailResponse buildDetail(ImportSupportRequest request, ImportBatch batch) {
    ImportSupportStatus status = request.getStatus();
    ImportSupportDetailResponse.SubmittedMapping submitted = null;
    Integer approvedVersionNo = null;
    if (request.getSubmittedMappingVersionId() != null) {
      ImportMappingVersion version =
          versionRepository.findById(request.getSubmittedMappingVersionId()).orElseThrow();
      submitted =
          new ImportSupportDetailResponse.SubmittedMapping(
              version.getVersionNo(),
              version.getSourceSystem(),
              version.getTargetType().name(),
              version.getConfirmedByType().name(),
              TimeRules.toDisplayOffset(version.getCreatedAt()),
              itemRepository.findByMappingVersionIdOrderById(version.getId()).stream()
                  .map(
                      item ->
                          new ImportSupportDetailResponse.SubmittedMapping.Item(
                              item.getSourceColumn(),
                              item.getTargetField(),
                              item.getConfidence() == null
                                  ? null
                                  : item.getConfidence().doubleValue()))
                  .toList());
      if (request.getApprovedMappingVersionId() != null) {
        approvedVersionNo = version.getVersionNo();
      }
    }
    ImportSupportDetailResponse.PreviewSummary preview = null;
    boolean reviewing =
        status == ImportSupportStatus.REVIEW || status == ImportSupportStatus.APPROVED;
    if (reviewing
        && request.getSubmittedMappingVersionId() != null
        && batch.getStatus() != ImportBatchStatus.UPLOADED
        && batch.getStatus().isEditable()) {
      var summary =
          previewService.summarize(
              request.getOfficeId(), batch, request.getSubmittedMappingVersionId());
      preview =
          new ImportSupportDetailResponse.PreviewSummary(
              summary.rowCount(),
              summary.warningCount(),
              summary.duplicateCount(),
              summary.relationMatchCount());
    }
    ImportSupportDetailResponse.ExecutionResult execution = null;
    boolean executed =
        batch.getStatus() == ImportBatchStatus.EXECUTING
            || batch.getStatus() == ImportBatchStatus.COMPLETED
            || batch.getStatus() == ImportBatchStatus.FAILED;
    if ((status == ImportSupportStatus.APPROVED || status == ImportSupportStatus.DELIVERED)
        && executed) {
      long succeeded =
          rowResultRepository.countByBatchIdAndResult(batch.getId(), ImportRowOutcome.SUCCEEDED);
      long failed =
          rowResultRepository.countByBatchIdAndResult(batch.getId(), ImportRowOutcome.FAILED);
      long total = batch.getRowCount() == null ? 0 : batch.getRowCount();
      execution =
          new ImportSupportDetailResponse.ExecutionResult(
              succeeded, failed, Math.max(0, total - succeeded - failed));
    }
    List<String> actions = new ArrayList<>();
    if (status.isCancellableByOwner()) {
      actions.add("CANCEL");
    }
    if (status == ImportSupportStatus.REVIEW) {
      actions.add("APPROVE");
      if (request.isChangeRequestAvailable()) {
        actions.add("REQUEST_CHANGES");
      }
    }
    return new ImportSupportDetailResponse(
        request.getId(),
        request.getImportBatchId(),
        request.getSourceSystem(),
        request.getEstimatedRowCount(),
        request.getQuoteAmount(),
        request.getQuoteScopeNote(),
        request.getPaymentOrderId(),
        request.getPaymentAmount(),
        TimeRules.toDisplayOffset(request.getPaidAt()),
        status.name(),
        request.getAssignedOperatorId() == null
            ? null
            : jdbcRepository.findOperatorName(request.getAssignedOperatorId()).orElse(null),
        TimeRules.toDisplayOffset(request.getProcessingStartedAt()),
        TimeRules.toDisplayOffset(request.getSubmittedAt()),
        request.getChangeRequestCount(),
        request.getChangeRequestReason(),
        TimeRules.toDisplayOffset(request.getChangeRequestedAt()),
        TimeRules.toDisplayOffset(request.getApprovedAt()),
        approvedVersionNo,
        TimeRules.toDisplayOffset(request.getDeliveredAt()),
        TimeRules.toDisplayOffset(request.getCancelledAt()),
        request.getCancelReason(),
        TimeRules.toDisplayOffset(request.getRejectedAt()),
        request.getRejectReason(),
        !status.isActive(),
        actions,
        batch.getStatus().name(),
        execution,
        submitted,
        preview,
        TimeRules.toDisplayOffset(request.getRequestedAt()),
        TimeRules.toDisplayOffset(request.getUpdatedAt()),
        request.getVersion());
  }
}
