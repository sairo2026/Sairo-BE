package com.sairo.be.domain.ops.service;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.legal.service.DeletionTargetService;
import com.sairo.be.domain.legal.service.DeletionTargetService.Outcome;
import com.sairo.be.domain.legal.service.DeletionTargetService.Plan;
import com.sairo.be.domain.ops.dto.request.OpsPrivacyOpsRequests.DeletionQueueCancelRequest;
import com.sairo.be.domain.ops.dto.request.OpsPrivacyOpsRequests.DeletionQueueExcludeRequest;
import com.sairo.be.domain.ops.dto.request.OpsPrivacyOpsRequests.DeletionQueueVerifyRequest;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.DeletionQueueCancelResponse;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.DeletionQueueExcludeResponse;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.DeletionQueueExecuteResponse;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.DeletionQueueItem;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.DeletionQueuePreviewResponse;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.DeletionQueueVerifyResponse;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.ObjectRef;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.TargetRef;
import com.sairo.be.domain.ops.repository.OperatorAccountRepository;
import com.sairo.be.domain.ops.repository.OpsAuditLogRepository;
import com.sairo.be.domain.ops.repository.OpsDeletionQueueRepository;
import com.sairo.be.domain.ops.repository.OpsDeletionQueueRepository.Filter;
import com.sairo.be.domain.ops.repository.OpsDeletionQueueRepository.Row;
import com.sairo.be.domain.ops.repository.OpsDeletionQueueRepository.StorageResults;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.security.OperatorStepUpState;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 파기 큐의 조회·미리보기·보존 예외·실행·검증·취소다(OPS-13). 실행자와 다른 활성 개인정보담당이 검증하기 전에는 결과를 SUCCESS로 적지 않는다. */
@Profile("!migrate")
@Service
public class OpsDeletionQueueService {

  private static final String TARGET = "DELETION_QUEUE";
  private static final Set<String> STATES =
      Set.of("PENDING", "AWAITING_VERIFICATION", "CANCELLED", "SUCCESS", "FAILED", "EXCLUDED");
  private static final String SUCCESS = "SUCCESS";
  private static final String EXCLUDED = "EXCLUDED";
  private static final String FAILED = "FAILED";

  private final OpsDeletionQueueRepository repository;
  private final DeletionTargetService targets;
  private final OperatorAccountRepository accounts;
  private final OpsAuditLogRepository auditLog;
  private final OperatorStepUpState stepUp;
  private final Clock clock;

  public OpsDeletionQueueService(
      OpsDeletionQueueRepository repository,
      DeletionTargetService targets,
      OperatorAccountRepository accounts,
      OpsAuditLogRepository auditLog,
      OperatorStepUpState stepUp,
      Clock clock) {
    this.repository = repository;
    this.targets = targets;
    this.accounts = accounts;
    this.auditLog = auditLog;
    this.stepUp = stepUp;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PageResponse<DeletionQueueItem> list(
      Long queueId, String state, String targetType, Long officeId, PageQuery page) {
    Filter filter =
        new Filter(
            OpsQueryParams.positiveId("queueId", queueId),
            OpsQueryParams.oneOf("state", state, STATES),
            OpsQueryParams.code("targetType", targetType),
            OpsQueryParams.positiveId("officeId", officeId));
    List<DeletionQueueItem> items =
        repository.list(filter, page.size(), (long) page.page() * page.size()).stream()
            .map(OpsDeletionQueueService::item)
            .toList();
    return PageResponse.of(items, page.page(), page.size(), repository.count(filter));
  }

  @Transactional(readOnly = true)
  public DeletionQueueItem get(long queueId) {
    return item(repository.find(queueId).orElseThrow(OpsDeletionQueueService::notFound));
  }

  @Transactional
  public DeletionQueuePreviewResponse preview(long operatorId, long queueId) {
    Row row = repository.lock(queueId).orElseThrow(OpsDeletionQueueService::notFound);
    Plan plan = targets.plan(row.targetType(), row.targetId(), clock.instant());
    long targetCount = row.result() == null ? plan.targetCount() : row.targetCount();
    long excludedCount = EXCLUDED.equals(row.result()) ? row.excludedCount() : 0;
    if (pending(row)) {
      repository.updateCounts(queueId, targetCount, excludedCount);
    }
    auditLog.insert(operatorId, "DELETION_QUEUE_PREVIEWED", TARGET, queueId, null, now());
    return new DeletionQueuePreviewResponse(
        targetCount,
        excludedCount,
        plan.storageTargets().stream()
            .map(target -> new TargetRef(target.targetType(), target.targetId()))
            .toList(),
        plan.searchIndexTargets().stream()
            .map(target -> new TargetRef(target.targetType(), target.targetId()))
            .toList(),
        plan.fileTargets().stream().map(ObjectRef::new).toList(),
        plan.temporaryExportTargets().stream().map(ObjectRef::new).toList(),
        Stream.concat(
                plan.holdReasons().stream(),
                row.exclusionReason() == null ? Stream.empty() : Stream.of(row.exclusionReason()))
            .toList());
  }

  @Transactional
  public DeletionQueueExcludeResponse exclude(
      long operatorId, long queueId, DeletionQueueExcludeRequest body) {
    Row row = repository.lock(queueId).orElseThrow(OpsDeletionQueueService::notFound);
    if (body.approvedByOperatorId() == operatorId) {
      throw new BusinessException(ErrorCode.SELF_APPROVAL_FORBIDDEN);
    }
    requireActivePrivacy(operatorId);
    if (!accounts.isActiveWithRole(body.approvedByOperatorId(), OperatorRole.PRIVACY)) {
      throw OpsQueryParams.invalid(
          "approvedByOperatorId", FieldErrorCode.NOT_ALLOWED, "활성 개인정보담당이어야 합니다.");
    }
    Instant now = now();
    Instant holdExpiresAt = body.holdExpiresAt().toInstant();
    Instant reviewAt = body.exclusionReviewedAt().toInstant();
    if (!holdExpiresAt.isAfter(now)) {
      throw OpsQueryParams.invalid(
          "holdExpiresAt", FieldErrorCode.OUT_OF_RANGE, "보류 만료 시각은 현재 이후여야 합니다.");
    }
    if (reviewAt.isAfter(holdExpiresAt)) {
      throw OpsQueryParams.invalid(
          "exclusionReviewedAt", FieldErrorCode.OUT_OF_RANGE, "재검토 예정일은 보류 만료 시각 이전이어야 합니다.");
    }
    if (!pending(row)) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    String reason = body.exclusionReason().strip();
    long targetCount = Math.max(row.targetCount(), 1);
    repository.exclude(
        queueId, reason, body.approvedByOperatorId(), holdExpiresAt, reviewAt, targetCount);
    auditLog.insert(
        operatorId,
        "DELETION_QUEUE_EXCLUDED",
        TARGET,
        queueId,
        reason + " (승인 " + body.approvedByOperatorId() + ")",
        now);
    return new DeletionQueueExcludeResponse(
        queueId, EXCLUDED, reason, toDisplayOffset(holdExpiresAt), toDisplayOffset(reviewAt));
  }

  @Transactional
  public DeletionQueueExecuteResponse execute(long operatorId, long queueId, HttpSession session) {
    Row row = repository.lock(queueId).orElseThrow(OpsDeletionQueueService::notFound);
    stepUp.require(session);
    requireActivePrivacy(operatorId);
    Instant now = now();
    boolean retry = row.executedAt() != null && FAILED.equals(row.result());
    if (row.cancelledAt() != null
        || (row.result() != null && !retry)
        || (row.executedAt() != null && !retry)
        || now.isBefore(row.scheduledAt())) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Plan plan = targets.plan(row.targetType(), row.targetId(), now);
    if (!plan.supported() || !plan.holdReasons().isEmpty()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    repository.updateCounts(queueId, plan.targetCount(), 0);
    Outcome outcome = targets.execute(row.targetType(), row.targetId(), plan);
    StorageResults results =
        new StorageResults(
            outcome.storageResult(),
            outcome.searchIndexResult(),
            outcome.fileResult(),
            outcome.temporaryExportResult());
    int retryCount = retry ? row.retryCount() + 1 : row.retryCount();
    repository.executed(queueId, operatorId, now, results, retryCount);
    auditLog.insert(
        operatorId,
        "DELETION_QUEUE_EXECUTED",
        TARGET,
        queueId,
        "저장소 "
            + results.storage()
            + ", 색인 "
            + results.searchIndex()
            + ", 파일 "
            + results.file()
            + ", 임시 내보내기 "
            + results.temporaryExport(),
        now);
    return new DeletionQueueExecuteResponse(
        queueId,
        null,
        toDisplayOffset(now),
        results.storage(),
        results.searchIndex(),
        results.file(),
        results.temporaryExport(),
        retryCount,
        null);
  }

  @Transactional
  public DeletionQueueVerifyResponse verify(
      long operatorId, long queueId, DeletionQueueVerifyRequest body) {
    Row row = repository.lock(queueId).orElseThrow(OpsDeletionQueueService::notFound);
    if (row.executedAt() == null || row.result() != null || row.cancelledAt() != null) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    if (row.executedByOperatorId() != null && row.executedByOperatorId() == operatorId) {
      throw new BusinessException(ErrorCode.SELF_APPROVAL_FORBIDDEN);
    }
    requireActivePrivacy(operatorId);
    if (body.storageResult() == null
        || body.searchIndexResult() == null
        || body.fileResult() == null
        || body.temporaryExportResult() == null) {
      throw new BusinessException(ErrorCode.VERIFICATION_INCOMPLETE);
    }
    StorageResults results =
        new StorageResults(
            body.storageResult(),
            body.searchIndexResult(),
            body.fileResult(),
            body.temporaryExportResult());
    boolean success =
        Stream.of(
                results.storage(), results.searchIndex(), results.file(), results.temporaryExport())
            .allMatch(value -> SUCCESS.equals(value) || EXCLUDED.equals(value));
    Instant now = now();
    Instant backup = body.backupExpiresAt() == null ? null : body.backupExpiresAt().toInstant();
    String result = success ? SUCCESS : FAILED;
    repository.verified(queueId, result, results, backup, operatorId, success ? now : null);
    auditLog.insert(
        operatorId,
        "DELETION_QUEUE_VERIFIED",
        TARGET,
        queueId,
        result + ", 증빙 " + body.evidence().strip(),
        now);
    return new DeletionQueueVerifyResponse(
        queueId,
        result,
        results.storage(),
        results.searchIndex(),
        results.file(),
        results.temporaryExport(),
        toDisplayOffset(backup),
        operatorId,
        success ? toDisplayOffset(now) : null);
  }

  @Transactional
  public DeletionQueueCancelResponse cancel(
      long operatorId, long queueId, DeletionQueueCancelRequest body) {
    Row row = repository.lock(queueId).orElseThrow(OpsDeletionQueueService::notFound);
    requireActivePrivacy(operatorId);
    if (!pending(row)) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant now = now();
    String reason = body.reason().strip();
    repository.cancel(queueId, operatorId, reason, now);
    auditLog.insert(operatorId, "DELETION_QUEUE_CANCELLED", TARGET, queueId, reason, now);
    return new DeletionQueueCancelResponse(queueId, toDisplayOffset(now), operatorId);
  }

  private static boolean pending(Row row) {
    return row.cancelledAt() == null && row.executedAt() == null && row.result() == null;
  }

  private void requireActivePrivacy(long operatorId) {
    if (!accounts.isActiveWithRole(operatorId, OperatorRole.PRIVACY)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
  }

  private static DeletionQueueItem item(Row row) {
    return new DeletionQueueItem(
        row.id(),
        row.targetType(),
        row.targetId(),
        row.officeId(),
        row.reason(),
        toDisplayOffset(row.scheduledAt()),
        toDisplayOffset(row.executedAt()),
        row.executedByOperatorId(),
        row.targetCount(),
        row.excludedCount(),
        row.result(),
        row.storageResult() == null && pending(row) ? "PENDING" : row.storageResult(),
        row.searchIndexResult() == null && pending(row) ? "PENDING" : row.searchIndexResult(),
        row.fileResult() == null && pending(row) ? "PENDING" : row.fileResult(),
        row.temporaryExportResult() == null && pending(row)
            ? "PENDING"
            : row.temporaryExportResult(),
        toDisplayOffset(row.backupExpiresAt()),
        row.retryCount(),
        row.exclusionReason(),
        row.holdApprovedByOperatorId(),
        toDisplayOffset(row.holdExpiresAt()),
        toDisplayOffset(row.exclusionReviewedAt()),
        row.verifiedByOperatorId(),
        toDisplayOffset(row.verifiedAt()),
        toDisplayOffset(row.cancelledAt()),
        row.cancelledByOperatorId(),
        row.cancelReason(),
        toDisplayOffset(row.createdAt()));
  }

  private static BusinessException notFound() {
    return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
