package com.sairo.be.domain.ops.service;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.ops.dto.request.RawAccessRequests.RawAccessRequestCreateRequest;
import com.sairo.be.domain.ops.dto.response.RawAccessResponses.RawAccessApproveResponse;
import com.sairo.be.domain.ops.dto.response.RawAccessResponses.RawAccessContentResponse;
import com.sairo.be.domain.ops.dto.response.RawAccessResponses.RawAccessCreateResponse;
import com.sairo.be.domain.ops.dto.response.RawAccessResponses.RawAccessDetailResponse;
import com.sairo.be.domain.ops.dto.response.RawAccessResponses.RawAccessExecuteResponse;
import com.sairo.be.domain.ops.dto.response.RawAccessResponses.RawAccessGrantDetail;
import com.sairo.be.domain.ops.dto.response.RawAccessResponses.RawAccessItem;
import com.sairo.be.domain.ops.dto.response.RawAccessResponses.RawAccessRejectResponse;
import com.sairo.be.domain.ops.dto.response.RawAccessResponses.RawAccessRevokeResponse;
import com.sairo.be.domain.ops.repository.OperatorAccountRepository;
import com.sairo.be.domain.ops.repository.OpsApprovalRequestRepository;
import com.sairo.be.domain.ops.repository.OpsApprovalRequestRepository.NewRequest;
import com.sairo.be.domain.ops.repository.OpsApprovalRequestRepository.Row;
import com.sairo.be.domain.ops.repository.OpsAuditLogRepository;
import com.sairo.be.domain.ops.repository.OpsRawAccessGrantRepository;
import com.sairo.be.domain.ops.repository.RawAccessTargetRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.port.ObjectStorage;
import com.sairo.be.global.security.OperatorPrincipal;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.security.OperatorStepUpState;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 원문 열람·다운로드 승인 요청과 실행(OPS-12)이다. */
@Profile("!migrate")
@Service
public class RawAccessService {

  static final Duration APPROVAL_VALIDITY = Duration.ofHours(24);
  static final Duration ACCESS_WINDOW = Duration.ofMinutes(60);
  static final Set<String> STATUSES =
      Set.of("PENDING", "APPROVED", "REJECTED", "EXPIRED", "INVALIDATED");
  private static final String RAW_DOWNLOAD = "RAW_DOWNLOAD";
  private static final String RAW_VIEW = "RAW_VIEW";
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  public record DownloadedFile(byte[] content, String sha256, String fileName) {}

  private sealed interface ExecuteOutcome {}

  private record Executed(RawAccessExecuteResponse response) implements ExecuteOutcome {}

  private record Invalidated() implements ExecuteOutcome {}

  private final OpsApprovalRequestRepository requests;
  private final OpsRawAccessGrantRepository grants;
  private final OperatorAccountRepository accounts;
  private final RawAccessTargetRepository queries;
  private final OpsAuditLogRepository auditLog;
  private final OperatorStepUpState stepUp;
  private final ObjectStorage objectStorage;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public RawAccessService(
      OpsApprovalRequestRepository requests,
      OpsRawAccessGrantRepository grants,
      OperatorAccountRepository accounts,
      RawAccessTargetRepository queries,
      OpsAuditLogRepository auditLog,
      OperatorStepUpState stepUp,
      ObjectStorage objectStorage,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.requests = requests;
    this.grants = grants;
    this.accounts = accounts;
    this.queries = queries;
    this.auditLog = auditLog;
    this.stepUp = stepUp;
    this.objectStorage = objectStorage;
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Transactional
  public RawAccessCreateResponse create(long operatorId, RawAccessRequestCreateRequest body) {
    Long officeId = body.officeId();
    if (body.targetType().equals("OFFICE")) {
      if (officeId != null && !officeId.equals(body.targetId())) {
        throw OpsQueryParams.invalid(
            "officeId", FieldErrorCode.NOT_ALLOWED, "사무소 대상이면 officeId는 targetId와 같아야 합니다.");
      }
      officeId = body.targetId();
    }
    if (body.basisType().equals("REPRESENTATIVE_REQUEST") && body.linkedInquiryId() == null) {
      throw OpsQueryParams.invalid(
          "linkedInquiryId", FieldErrorCode.REQUIRED, "대표 요청 근거에는 연결 문의가 필요합니다.");
    }
    if (body.linkedInquiryId() != null && !queries.inquiryExists(body.linkedInquiryId())) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    if (officeId != null && !queries.officeExists(officeId)) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    if (!queries.targetExists(body.targetType(), body.targetId())) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    Long beneficiary = body.beneficiaryOperatorId();
    if (beneficiary != null && beneficiary == operatorId) {
      beneficiary = null;
    }
    if (beneficiary != null && !accounts.isActive(beneficiary)) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    Instant now = clock.instant();
    long id =
        requests.insert(
            new NewRequest(
                body.requestType(),
                operatorId,
                beneficiary,
                body.targetType(),
                body.targetId(),
                body.purpose().strip(),
                body.scope().strip(),
                body.basisType(),
                body.basisNote().strip(),
                body.linkedInquiryId(),
                officeId),
            now);
    Row row = requests.find(id, now).orElseThrow();
    audit(operatorId, "RAW_ACCESS_REQUESTED", row, now);
    return new RawAccessCreateResponse(
        row.id(),
        row.requestType(),
        row.targetType(),
        row.targetId(),
        row.purpose(),
        row.scope(),
        row.status(),
        row.beneficiary(),
        toDisplayOffset(row.createdAt()));
  }

  @Transactional(readOnly = true)
  public PageResponse<RawAccessItem> list(
      OperatorPrincipal principal, String scope, String status, PageQuery page) {
    String resolvedScope = scope == null || scope.isBlank() ? "MINE" : scope.strip();
    if (!resolvedScope.equals("MINE") && !resolvedScope.equals("APPROVABLE")) {
      throw OpsQueryParams.invalid("scope", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 scope 값입니다.");
    }
    String resolvedStatus = OpsQueryParams.oneOf("status", status, STATUSES);
    boolean approvable = resolvedScope.equals("APPROVABLE");
    if (approvable && !principal.hasRole(OperatorRole.PRIVACY)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    Instant now = clock.instant();
    List<RawAccessItem> items =
        requests
            .list(
                approvable,
                principal.operatorId(),
                resolvedStatus,
                now,
                page.size(),
                (long) page.page() * page.size())
            .stream()
            .map(RawAccessService::toItem)
            .toList();
    return PageResponse.of(
        items,
        page.page(),
        page.size(),
        requests.count(approvable, principal.operatorId(), resolvedStatus, now));
  }

  @Transactional(readOnly = true)
  public RawAccessDetailResponse detail(OperatorPrincipal principal, long requestId) {
    Row row = find(requestId);
    long me = principal.operatorId();
    boolean party = row.requestedBy() == me || Long.valueOf(me).equals(row.beneficiary());
    if (!party
        && !principal.hasRole(OperatorRole.PRIVACY)
        && !principal.hasRole(OperatorRole.SECURITY_ADMIN)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    RawAccessGrantDetail grant =
        grants
            .findByRequest(requestId)
            .map(
                g ->
                    new RawAccessGrantDetail(
                        g.id(),
                        toDisplayOffset(g.executedAt()),
                        toDisplayOffset(g.expiresAt()),
                        fileSize(g.fileObjectKey()),
                        g.fileSha256(),
                        toDisplayOffset(g.downloadedAt()),
                        toDisplayOffset(g.revokedAt()),
                        g.revokedByName(),
                        g.revokeReason()))
            .orElse(null);
    return new RawAccessDetailResponse(
        row.id(),
        row.requestType(),
        row.targetType(),
        row.targetId(),
        row.purpose(),
        row.scope(),
        row.status(),
        row.requestedBy(),
        row.requestedByName(),
        row.beneficiary(),
        row.approvedByName(),
        toDisplayOffset(row.approvedAt()),
        toDisplayOffset(row.expiresAt()),
        row.grantId(),
        toDisplayOffset(row.createdAt()),
        row.basisType(),
        row.basisNote(),
        row.linkedInquiryId(),
        row.officeId(),
        row.rejectedByName(),
        toDisplayOffset(row.rejectedAt()),
        row.rejectReason(),
        grant);
  }

  @Transactional
  public RawAccessApproveResponse approve(long operatorId, long requestId) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Row row = lock(requestId, now);
    requireNotParty(row, operatorId);
    requireActivePrivacy(operatorId);
    if (!row.status().equals("PENDING")) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant expiresAt = now.plus(APPROVAL_VALIDITY);
    requests.approve(requestId, operatorId, now, expiresAt);
    audit(operatorId, "RAW_ACCESS_APPROVED", row, now);
    return new RawAccessApproveResponse(
        requestId, row.requestType(), "APPROVED", toDisplayOffset(now), toDisplayOffset(expiresAt));
  }

  @Transactional
  public RawAccessRejectResponse reject(long operatorId, long requestId, String reason) {
    Instant now = clock.instant();
    Row row = lock(requestId, now);
    requireNotParty(row, operatorId);
    requireActivePrivacy(operatorId);
    if (!row.status().equals("PENDING")) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    requests.reject(requestId, operatorId, reason.strip(), now);
    audit(operatorId, "RAW_ACCESS_REJECTED", row, now);
    return new RawAccessRejectResponse(requestId, "REJECTED", toDisplayOffset(now));
  }

  public RawAccessExecuteResponse execute(long operatorId, long requestId, HttpSession session) {
    ExecuteOutcome outcome =
        transactions.execute(status -> executeInTransaction(operatorId, requestId, session));
    if (outcome instanceof Executed executed) {
      return executed.response();
    }
    throw new BusinessException(ErrorCode.APPROVAL_INVALIDATED);
  }

  private ExecuteOutcome executeInTransaction(
      long operatorId, long requestId, HttpSession session) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Row row = lock(requestId, now);
    if (row.effectiveExecutor() != operatorId) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    stepUp.require(session);
    if (row.grantId() != null) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    switch (row.status()) {
      case "APPROVED" -> {}
      case "EXPIRED" -> {
        requests.markStatus(requestId, "EXPIRED");
        return new Invalidated();
      }
      case "INVALIDATED" -> {
        return new Invalidated();
      }
      default -> throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    boolean valid =
        row.approvedBy() != null
            && accounts.isActiveWithRole(row.approvedBy(), OperatorRole.PRIVACY)
            && accounts.isActive(row.requestedBy())
            && (row.beneficiary() == null || accounts.isActive(row.beneficiary()))
            && row.targetId() != null
            && queries.targetExists(row.targetType(), row.targetId());
    if (!valid) {
      requests.markStatus(requestId, "INVALIDATED");
      auditLog.insert(
          operatorId,
          "RAW_ACCESS_INVALIDATED",
          row.targetType(),
          row.targetId(),
          "requestId=" + row.id(),
          now);
      return new Invalidated();
    }
    String key = null;
    String sha = null;
    Long size = null;
    if (row.requestType().equals(RAW_DOWNLOAD)) {
      byte[] content =
          queries
              .targetSnapshot(row.targetType(), row.targetId())
              .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND))
              .getBytes(StandardCharsets.UTF_8);
      key = "ops/raw-access/" + row.id() + "/" + UUID.randomUUID() + ".json";
      sha = sha256(content);
      size = (long) content.length;
      objectStorage.put(key, content, "application/json");
    }
    Instant expiresAt = now.plus(ACCESS_WINDOW);
    long grantId =
        grants.insert(
            row.id(),
            row.requestType(),
            row.targetType(),
            row.targetId(),
            operatorId,
            now,
            expiresAt,
            key,
            sha);
    auditLog.insert(
        operatorId,
        "RAW_ACCESS_EXECUTED",
        row.targetType(),
        row.targetId(),
        "requestId=" + row.id() + " grantId=" + grantId + " requestType=" + row.requestType(),
        now);
    return new Executed(
        new RawAccessExecuteResponse(
            grantId,
            row.id(),
            row.requestType(),
            toDisplayOffset(now),
            toDisplayOffset(expiresAt),
            size,
            sha,
            size == null ? null : "/api/ops/raw-access-grants/" + grantId + "/file"));
  }

  @Transactional
  public RawAccessRevokeResponse revoke(
      long operatorId, long grantId, String reason, HttpSession session) {
    stepUp.require(session);
    var grant =
        grants.lock(grantId).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    Instant now = clock.instant();
    if (grant.revokedAt() != null || !grant.expiresAt().isAfter(now)) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    grants.revoke(grantId, operatorId, reason.strip(), now);
    auditLog.insert(
        operatorId,
        "RAW_ACCESS_REVOKED",
        grant.targetType(),
        grant.targetId(),
        "requestId=" + grant.approvalRequestId() + " grantId=" + grantId,
        now);
    return new RawAccessRevokeResponse(grantId, toDisplayOffset(now), operatorId);
  }

  @Transactional
  public DownloadedFile download(long operatorId, long grantId, HttpSession session) {
    var grant =
        grants.lock(grantId).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (grant.executedBy() != operatorId) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    if (!grant.requestType().equals(RAW_DOWNLOAD)) {
      throw new BusinessException(ErrorCode.DOWNLOAD_NOT_APPROVED);
    }
    stepUp.require(session);
    if (grant.downloadedAt() != null) {
      throw new BusinessException(ErrorCode.RAW_DOWNLOAD_ALREADY_USED);
    }
    Instant now = clock.instant();
    requireOpenWindow(grant, now);
    byte[] content =
        objectStorage
            .find(grant.fileObjectKey())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    grants.markDownloaded(grantId, now);
    auditLog.insert(
        operatorId,
        "RAW_ACCESS_DOWNLOADED",
        grant.targetType(),
        grant.targetId(),
        "requestId=" + grant.approvalRequestId() + " grantId=" + grantId,
        now);
    return new DownloadedFile(content, grant.fileSha256(), "raw-access-" + grantId + ".json");
  }

  @Transactional
  public RawAccessContentResponse view(long operatorId, long grantId, HttpSession session) {
    var grant =
        grants.find(grantId).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (grant.executedBy() != operatorId || !grant.requestType().equals(RAW_VIEW)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    stepUp.require(session);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    requireOpenWindow(grant, now);
    if (grant.targetId() == null) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    String snapshot =
        queries
            .targetSnapshot(grant.targetType(), grant.targetId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    auditLog.insert(
        operatorId,
        "RAW_ACCESS_VIEWED",
        grant.targetType(),
        grant.targetId(),
        "requestId=" + grant.approvalRequestId() + " grantId=" + grantId,
        now);
    return new RawAccessContentResponse(
        grantId,
        grant.approvalRequestId(),
        grant.targetType(),
        grant.targetId(),
        toDisplayOffset(grant.expiresAt()),
        toDisplayOffset(now),
        MAPPER.readTree(snapshot));
  }

  private static void requireOpenWindow(OpsRawAccessGrantRepository.Row grant, Instant now) {
    if (grant.revokedAt() != null) {
      throw new BusinessException(ErrorCode.RAW_ACCESS_REVOKED);
    }
    if (!grant.expiresAt().isAfter(now)) {
      throw new BusinessException(ErrorCode.RAW_ACCESS_EXPIRED);
    }
  }

  private Row find(long requestId) {
    return requests
        .find(requestId, clock.instant())
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  private Row lock(long requestId, Instant now) {
    return requests
        .lock(requestId, now)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  /** 승인·거절자는 요청자·수혜자와 달라야 한다. 역할 조합과 무관하게 운영자 ID로 비교한다. */
  private static void requireNotParty(Row row, long operatorId) {
    if (row.requestedBy() == operatorId || Long.valueOf(operatorId).equals(row.beneficiary())) {
      throw new BusinessException(ErrorCode.SELF_APPROVAL_FORBIDDEN);
    }
  }

  private void requireActivePrivacy(long operatorId) {
    if (!accounts.isActiveWithRole(operatorId, OperatorRole.PRIVACY)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
  }

  private void audit(long operatorId, String action, Row row, Instant now) {
    auditLog.insert(
        operatorId,
        action,
        row.targetType(),
        row.targetId(),
        "requestId=" + row.id() + " requestType=" + row.requestType(),
        now);
  }

  private Long fileSize(String key) {
    if (key == null) {
      return null;
    }
    Optional<byte[]> content = objectStorage.find(key);
    return content.map(bytes -> (long) bytes.length).orElse(null);
  }

  private static RawAccessItem toItem(Row row) {
    return new RawAccessItem(
        row.id(),
        row.requestType(),
        row.targetType(),
        row.targetId(),
        row.purpose(),
        row.scope(),
        row.status(),
        row.requestedBy(),
        row.requestedByName(),
        row.beneficiary(),
        row.approvedByName(),
        toDisplayOffset(row.approvedAt()),
        toDisplayOffset(row.expiresAt()),
        row.grantId(),
        toDisplayOffset(row.createdAt()));
  }

  static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
