package com.sairo.be.domain.ops.service;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.migration.dto.request.ImportMappingConfirmRequest;
import com.sairo.be.domain.migration.entity.ImportBatch;
import com.sairo.be.domain.migration.entity.ImportBatchStatus;
import com.sairo.be.domain.migration.entity.ImportMappingVersion;
import com.sairo.be.domain.migration.entity.ImportSupportRequest;
import com.sairo.be.domain.migration.entity.ImportSupportStatus;
import com.sairo.be.domain.migration.entity.MappingConfirmerType;
import com.sairo.be.domain.migration.repository.ImportBatchRepository;
import com.sairo.be.domain.migration.repository.ImportMappingItemRepository;
import com.sairo.be.domain.migration.repository.ImportMappingVersionRepository;
import com.sairo.be.domain.migration.repository.ImportSupportRequestRepository;
import com.sairo.be.domain.migration.service.ImportFileReader;
import com.sairo.be.domain.migration.service.ImportPreviewService;
import com.sairo.be.domain.migration.service.ImportRequestParser;
import com.sairo.be.domain.migration.service.ImportSupportOperatorService;
import com.sairo.be.domain.migration.service.ImportSupportOperatorService.Transition;
import com.sairo.be.domain.ops.dto.request.OpsImportSupportRequests.ImportSupportQuoteRequest;
import com.sairo.be.domain.ops.dto.request.OpsImportSupportRequests.ImportSupportRejectRequest;
import com.sairo.be.domain.ops.dto.response.OpsImportSupportResponses.CurrentMapping;
import com.sairo.be.domain.ops.dto.response.OpsImportSupportResponses.ImportSupportDetailResponse;
import com.sairo.be.domain.ops.dto.response.OpsImportSupportResponses.ImportSupportExecuteResponse;
import com.sairo.be.domain.ops.dto.response.OpsImportSupportResponses.ImportSupportItem;
import com.sairo.be.domain.ops.dto.response.OpsImportSupportResponses.ImportSupportMappingResponse;
import com.sairo.be.domain.ops.dto.response.OpsImportSupportResponses.ImportSupportRejectResponse;
import com.sairo.be.domain.ops.dto.response.OpsImportSupportResponses.ImportSupportStartResponse;
import com.sairo.be.domain.ops.dto.response.OpsImportSupportResponses.ImportSupportSubmitReviewResponse;
import com.sairo.be.domain.ops.dto.response.OpsImportSupportResponses.ImportSupportSummaryResponse;
import com.sairo.be.domain.ops.dto.response.OpsImportSupportResponses.MappingHistoryItem;
import com.sairo.be.domain.ops.dto.response.OpsImportSupportResponses.MappingItem;
import com.sairo.be.domain.ops.dto.response.OpsImportSupportResponses.PreviewSummary;
import com.sairo.be.domain.ops.repository.OperatorAccountRepository;
import com.sairo.be.domain.ops.repository.OpsAuditLogRepository;
import com.sairo.be.domain.ops.repository.OpsImportSupportQueryRepository;
import com.sairo.be.domain.ops.repository.OpsSupportQueryRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.support.IfMatchVersion;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 이전담당의 유료 이전 지원 처리다(OPS-07). 상태 규칙은 migration의 ImportSupportOperatorService가 갖는다. */
@Profile("!migrate")
@Service
public class OpsImportSupportService {

  static final String TARGET = "IMPORT_SUPPORT_REQUEST";
  private static final Set<String> STATUSES =
      Arrays.stream(ImportSupportStatus.values()).map(Enum::name).collect(Collectors.toSet());

  private final ImportSupportOperatorService operatorService;
  private final ImportSupportRequestRepository requests;
  private final ImportBatchRepository batches;
  private final ImportMappingVersionRepository versions;
  private final ImportMappingItemRepository items;
  private final ImportFileReader fileReader;
  private final ImportPreviewService previewService;
  private final OpsImportSupportQueryRepository query;
  private final OpsSupportQueryRepository supportQuery;
  private final OperatorAccountRepository accounts;
  private final OpsAuditLogRepository auditLog;
  private final Clock clock;

  public OpsImportSupportService(
      ImportSupportOperatorService operatorService,
      ImportSupportRequestRepository requests,
      ImportBatchRepository batches,
      ImportMappingVersionRepository versions,
      ImportMappingItemRepository items,
      ImportFileReader fileReader,
      ImportPreviewService previewService,
      OpsImportSupportQueryRepository query,
      OpsSupportQueryRepository supportQuery,
      OperatorAccountRepository accounts,
      OpsAuditLogRepository auditLog,
      Clock clock) {
    this.operatorService = operatorService;
    this.requests = requests;
    this.batches = batches;
    this.versions = versions;
    this.items = items;
    this.fileReader = fileReader;
    this.previewService = previewService;
    this.query = query;
    this.supportQuery = supportQuery;
    this.accounts = accounts;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PageResponse<ImportSupportItem> list(
      long operatorId, String status, Boolean assignedToMe, Long officeId, PageQuery page) {
    String resolvedStatus = OpsQueryParams.oneOf("status", status, STATUSES);
    Long resolvedOffice = OpsQueryParams.positiveId("officeId", officeId);
    Long assigned = Boolean.TRUE.equals(assignedToMe) ? operatorId : null;
    List<ImportSupportItem> found =
        query
            .list(
                resolvedStatus,
                assigned,
                resolvedOffice,
                page.size(),
                (long) page.page() * page.size())
            .stream()
            .map(
                row ->
                    new ImportSupportItem(
                        row.id(),
                        row.officeId(),
                        row.importBatchId(),
                        row.assignedOperatorName(),
                        row.sourceSystem(),
                        row.estimatedRowCount(),
                        row.quoteAmount(),
                        row.status(),
                        toDisplayOffset(row.requestedAt()),
                        toDisplayOffset(row.deliveredAt())))
            .toList();
    return PageResponse.of(
        found, page.page(), page.size(), query.count(resolvedStatus, assigned, resolvedOffice));
  }

  @Transactional
  public ImportSupportDetailResponse detail(long operatorId, long requestId) {
    ImportSupportRequest request = find(requestId);
    ImportBatch batch = batches.findById(request.getImportBatchId()).orElseThrow();
    List<ImportMappingVersion> all = versions.findByImportBatchIdOrderByVersionNo(batch.getId());
    ImportMappingVersion latest = all.isEmpty() ? null : all.get(all.size() - 1);
    Integer approvedNo = versionNo(request.getApprovedMappingVersionId());
    auditLog.insert(operatorId, "IMPORT_SUPPORT_VIEWED", TARGET, requestId, null, now());
    return new ImportSupportDetailResponse(
        request.getId(),
        request.getOfficeId(),
        supportQuery.officeName(request.getOfficeId()).orElse(null),
        batch.getId(),
        batch.getStatus().name(),
        batch.getRowCount(),
        request.getSourceSystem(),
        request.getEstimatedRowCount(),
        request.getQuoteAmount(),
        request.getQuoteScopeNote(),
        request.getStatus().name(),
        request.getAssignedOperatorId(),
        request.getAssignedOperatorId() == null
            ? null
            : query.operatorName(request.getAssignedOperatorId()).orElse(null),
        toDisplayOffset(request.getProcessingStartedAt()),
        toDisplayOffset(request.getSubmittedAt()),
        request.getChangeRequestCount(),
        request.getChangeRequestReason(),
        toDisplayOffset(request.getChangeRequestedAt()),
        toDisplayOffset(request.getApprovedAt()),
        approvedNo,
        toDisplayOffset(batch.getExecutedAt()),
        toDisplayOffset(request.getDeliveredAt()),
        toDisplayOffset(request.getCancelledAt()),
        toDisplayOffset(request.getRejectedAt()),
        request.getRejectReason(),
        latest == null ? null : currentMapping(latest),
        sourceColumns(batch),
        all.stream().map(this::historyItem).toList(),
        latest == null ? null : preview(request, batch, latest.getId()),
        allowedActions(operatorId, request, batch, latest != null),
        toDisplayOffset(request.getRequestedAt()),
        toDisplayOffset(request.getUpdatedAt()),
        request.getVersion());
  }

  @Transactional
  public ImportSupportSummaryResponse quote(
      long operatorId, long requestId, String ifMatch, ImportSupportQuoteRequest body) {
    long expected = IfMatchVersion.parse(ifMatch);
    requireActiveMigration(operatorId);
    String scopeNote = body.scopeNote() == null ? null : body.scopeNote().strip();
    operatorService.quote(requestId, body.quoteAmount(), scopeNote, expected);
    ImportSupportRequest request = find(requestId);
    audit(operatorId, "IMPORT_SUPPORT_QUOTED", requestId, "견적 " + body.quoteAmount() + "원");
    return summary(request);
  }

  @Transactional
  public ImportSupportRejectResponse reject(
      long operatorId, long requestId, String ifMatch, ImportSupportRejectRequest body) {
    long expected = IfMatchVersion.parse(ifMatch);
    requireActiveMigration(operatorId);
    String reason = body.reason().strip();
    Transition result = operatorService.reject(requestId, operatorId, reason, expected);
    ImportSupportRequest request = find(requestId);
    audit(operatorId, "IMPORT_SUPPORT_REJECTED", requestId, reason);
    return new ImportSupportRejectResponse(
        result.requestId(),
        result.status().name(),
        toDisplayOffset(request.getRejectedAt()),
        toDisplayOffset(result.updatedAt()),
        result.version());
  }

  @Transactional
  public ImportSupportStartResponse start(long operatorId, long requestId, String ifMatch) {
    long expected = IfMatchVersion.parse(ifMatch);
    requireActiveMigration(operatorId);
    Transition result = operatorService.start(requestId, operatorId, expected);
    ImportSupportRequest request = find(requestId);
    audit(operatorId, "IMPORT_SUPPORT_STARTED", requestId, null);
    return new ImportSupportStartResponse(
        result.requestId(),
        result.status().name(),
        operatorId,
        toDisplayOffset(request.getProcessingStartedAt()),
        toDisplayOffset(result.updatedAt()),
        result.version());
  }

  @Transactional
  public ImportSupportMappingResponse saveMapping(
      long operatorId, long requestId, String ifMatch, JsonNode body) {
    long expected = IfMatchVersion.parse(ifMatch);
    String targetType =
        query
            .batchTargetType(requestId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    ImportMappingConfirmRequest mapping = parseMapping(body, targetType);
    requireActiveMigration(operatorId);
    operatorService.saveMapping(requestId, operatorId, mapping, expected);
    ImportSupportRequest request = find(requestId);
    ImportMappingVersion latest =
        versions
            .findFirstByImportBatchIdOrderByVersionNoDesc(request.getImportBatchId())
            .orElseThrow();
    ImportBatch saved = batches.findById(request.getImportBatchId()).orElseThrow();
    audit(operatorId, "IMPORT_SUPPORT_MAPPING_SAVED", requestId, "매핑 버전 " + latest.getVersionNo());
    return new ImportSupportMappingResponse(
        request.getId(),
        request.getStatus().name(),
        latest.getVersionNo(),
        preview(request, saved, latest.getId()),
        toDisplayOffset(request.getUpdatedAt()),
        request.getVersion());
  }

  @Transactional
  public ImportSupportSubmitReviewResponse submitReview(
      long operatorId, long requestId, String ifMatch) {
    long expected = IfMatchVersion.parse(ifMatch);
    requireActiveMigration(operatorId);
    Transition result = operatorService.submitReview(requestId, operatorId, expected);
    ImportSupportRequest request = find(requestId);
    Integer versionNo = versionNo(request.getSubmittedMappingVersionId());
    audit(operatorId, "IMPORT_SUPPORT_REVIEW_SUBMITTED", requestId, "매핑 버전 " + versionNo);
    return new ImportSupportSubmitReviewResponse(
        result.requestId(),
        result.status().name(),
        versionNo,
        toDisplayOffset(request.getSubmittedAt()),
        toDisplayOffset(result.updatedAt()),
        result.version());
  }

  @Transactional
  public ImportSupportExecuteResponse execute(long operatorId, long requestId, String ifMatch) {
    long expected = IfMatchVersion.parse(ifMatch);
    requireActiveMigration(operatorId);
    var started = operatorService.execute(requestId, operatorId, expected);
    ImportSupportRequest request = find(requestId);
    Integer versionNo = versionNo(request.getApprovedMappingVersionId());
    audit(operatorId, "IMPORT_SUPPORT_EXECUTED", requestId, "매핑 버전 " + versionNo);
    return new ImportSupportExecuteResponse(
        request.getId(),
        request.getImportBatchId(),
        request.getStatus().name(),
        started.status(),
        versionNo,
        toDisplayOffset(request.getUpdatedAt()),
        request.getVersion());
  }

  @Transactional
  public ImportSupportSummaryResponse deliver(long operatorId, long requestId, String ifMatch) {
    long expected = IfMatchVersion.parse(ifMatch);
    requireActiveMigration(operatorId);
    operatorService.deliver(requestId, operatorId, expected);
    ImportSupportRequest request = find(requestId);
    audit(operatorId, "IMPORT_SUPPORT_DELIVERED", requestId, null);
    return summary(request);
  }

  private static ImportMappingConfirmRequest parseMapping(JsonNode body, String batchTargetType) {
    if (body == null || !body.isObject()) {
      throw OpsQueryParams.invalid("body", FieldErrorCode.TYPE_MISMATCH, "JSON 객체여야 합니다.");
    }
    ObjectNode copy = ((ObjectNode) body).deepCopy();
    JsonNode targetType = copy.remove("targetType");
    if (targetType != null
        && !targetType.isNull()
        && (!targetType.isString() || !targetType.asString().equals(batchTargetType))) {
      throw OpsQueryParams.invalid(
          "targetType", FieldErrorCode.INVALID_VALUE, "업로드 때 정한 이전 유형과 같아야 합니다.");
    }
    return ImportRequestParser.mappingConfirm(copy);
  }

  private void requireActiveMigration(long operatorId) {
    if (!accounts.isActiveWithRole(operatorId, OperatorRole.MIGRATION)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
  }

  private ImportSupportRequest find(long requestId) {
    return requests
        .findById(requestId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  private Integer versionNo(Long mappingVersionId) {
    return mappingVersionId == null
        ? null
        : versions.findById(mappingVersionId).map(ImportMappingVersion::getVersionNo).orElse(null);
  }

  private CurrentMapping currentMapping(ImportMappingVersion version) {
    return new CurrentMapping(
        version.getVersionNo(),
        version.getSourceSystem(),
        version.getTargetType().name(),
        version.getConfirmedByType().name(),
        toDisplayOffset(version.getCreatedAt()),
        items.findByMappingVersionIdOrderById(version.getId()).stream()
            .map(
                item ->
                    new MappingItem(
                        item.getSourceColumn(),
                        item.getTargetField(),
                        item.getConfidence() == null ? null : item.getConfidence().doubleValue()))
            .toList());
  }

  private MappingHistoryItem historyItem(ImportMappingVersion version) {
    String name =
        version.getConfirmedByType() == MappingConfirmerType.OPERATOR
            ? query.operatorName(version.getConfirmedByOperatorId()).orElse(null)
            : query.membershipName(version.getConfirmedByMembershipId()).orElse(null);
    return new MappingHistoryItem(
        version.getVersionNo(),
        version.getConfirmedByType().name(),
        name,
        toDisplayOffset(version.getCreatedAt()));
  }

  private List<String> sourceColumns(ImportBatch batch) {
    if (!batch.getStatus().isEditable()) {
      return List.of();
    }
    return List.copyOf(fileReader.readSelectedSheet(batch).headers());
  }

  private PreviewSummary preview(
      ImportSupportRequest request, ImportBatch batch, Long mappingVersionId) {
    if (batch.getStatus() == ImportBatchStatus.UPLOADED || !batch.getStatus().isEditable()) {
      return null;
    }
    var summary = previewService.summarize(request.getOfficeId(), batch, mappingVersionId);
    return new PreviewSummary(
        summary.rowCount(),
        summary.warningCount(),
        summary.duplicateCount(),
        summary.relationMatchCount());
  }

  private static List<String> allowedActions(
      long operatorId, ImportSupportRequest request, ImportBatch batch, boolean hasMapping) {
    List<String> actions = new ArrayList<>();
    ImportSupportStatus status = request.getStatus();
    boolean mine = Long.valueOf(operatorId).equals(request.getAssignedOperatorId());
    if (status == ImportSupportStatus.REQUESTED || status == ImportSupportStatus.QUOTED) {
      actions.add("QUOTE");
      actions.add("REJECT");
    }
    if (status == ImportSupportStatus.PAID) {
      actions.add("START");
    }
    if (status == ImportSupportStatus.PROCESSING && mine) {
      actions.add("EDIT_MAPPING");
      if (hasMapping) {
        actions.add("SUBMIT_REVIEW");
      }
    }
    if (status == ImportSupportStatus.APPROVED && mine) {
      if (batch.getStatus() == ImportBatchStatus.MAPPING
          || batch.getStatus() == ImportBatchStatus.PREVIEW) {
        actions.add("EXECUTE");
      }
      if (batch.getStatus() == ImportBatchStatus.COMPLETED) {
        actions.add("DELIVER");
      }
    }
    return actions;
  }

  private static ImportSupportSummaryResponse summary(ImportSupportRequest request) {
    return new ImportSupportSummaryResponse(
        request.getId(),
        request.getOfficeId(),
        request.getSourceSystem(),
        request.getEstimatedRowCount(),
        request.getQuoteAmount(),
        request.getStatus().name(),
        toDisplayOffset(request.getRequestedAt()),
        toDisplayOffset(request.getDeliveredAt()),
        toDisplayOffset(request.getUpdatedAt()),
        request.getVersion());
  }

  private void audit(long operatorId, String action, long requestId, String detail) {
    auditLog.insert(operatorId, action, TARGET, requestId, detail, now());
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
