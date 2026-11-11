package com.sairo.be.domain.ops.service;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.legal.entity.IdentityStatus;
import com.sairo.be.domain.legal.entity.PrivacyRequestStatus;
import com.sairo.be.domain.legal.entity.PrivacyRequestType;
import com.sairo.be.domain.legal.service.PrivacyRequestProcessService;
import com.sairo.be.domain.legal.service.PrivacyRequestProcessService.Action;
import com.sairo.be.domain.legal.service.PrivacyRequestProcessService.Command;
import com.sairo.be.domain.legal.service.PrivacyRequestProcessService.Result;
import com.sairo.be.domain.ops.dto.request.OpsPrivacyOpsRequests.ArchiveGrantTransferRequest;
import com.sairo.be.domain.ops.dto.request.OpsPrivacyOpsRequests.PrivacyRequestProcessRequest;
import com.sairo.be.domain.ops.dto.request.OpsPrivacyOpsRequests.ReturnGrantTransferRequest;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.ArchiveGrantItem;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.ArchiveGrantTransferResponse;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.PrivacyRequestDetailResponse;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.PrivacyRequestEventItem;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.PrivacyRequestItem;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.PrivacyRequestProcessResponse;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.ReturnGrantItem;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyOpsResponses.ReturnGrantTransferResponse;
import com.sairo.be.domain.ops.repository.OperatorAccountRepository;
import com.sairo.be.domain.ops.repository.OpsAuditLogRepository;
import com.sairo.be.domain.ops.repository.OpsPrivacyOpsRepository;
import com.sairo.be.domain.ops.repository.OpsPrivacyOpsRepository.GrantRow;
import com.sairo.be.domain.ops.repository.OpsPrivacyOpsRepository.NewGrant;
import com.sairo.be.domain.ops.repository.OpsPrivacyOpsRepository.RequestFilter;
import com.sairo.be.domain.ops.repository.OpsPrivacyOpsRepository.RequestRow;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.security.OperatorStepUpState;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 개인정보 권리 요청 처리와 반환·보관권한 현황·권한자 변경이다(OPS-07). */
@Profile("!migrate")
@Service
public class OpsPrivacyOpsService {

  static final Duration RETURN_GRANT_VALIDITY = Duration.ofDays(30);
  private static final Set<String> STATUSES =
      Arrays.stream(PrivacyRequestStatus.values()).map(Enum::name).collect(Collectors.toSet());
  private static final Set<String> TYPES =
      Arrays.stream(PrivacyRequestType.values()).map(Enum::name).collect(Collectors.toSet());
  private static final Set<String> CLOSED = Set.of("COMPLETED", "REJECTED");

  private final OpsPrivacyOpsRepository repository;
  private final PrivacyRequestProcessService processService;
  private final OperatorAccountRepository accounts;
  private final OpsAuditLogRepository auditLog;
  private final OperatorStepUpState stepUp;
  private final AdvisoryLock advisoryLock;
  private final Clock clock;

  public OpsPrivacyOpsService(
      OpsPrivacyOpsRepository repository,
      PrivacyRequestProcessService processService,
      OperatorAccountRepository accounts,
      OpsAuditLogRepository auditLog,
      OperatorStepUpState stepUp,
      AdvisoryLock advisoryLock,
      Clock clock) {
    this.repository = repository;
    this.processService = processService;
    this.accounts = accounts;
    this.auditLog = auditLog;
    this.stepUp = stepUp;
    this.advisoryLock = advisoryLock;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PageResponse<PrivacyRequestItem> requests(
      long operatorId,
      String status,
      String requestType,
      Long officeId,
      Boolean assignedToMe,
      PageQuery page) {
    RequestFilter filter =
        new RequestFilter(
            OpsQueryParams.oneOf("status", status, STATUSES),
            OpsQueryParams.oneOf("requestType", requestType, TYPES),
            OpsQueryParams.positiveId("officeId", officeId),
            Boolean.TRUE.equals(assignedToMe) ? operatorId : null);
    Instant now = clock.instant();
    List<PrivacyRequestItem> items =
        repository.requests(filter, page.size(), (long) page.page() * page.size()).stream()
            .map(row -> item(row, now))
            .toList();
    return PageResponse.of(items, page.page(), page.size(), repository.countRequests(filter));
  }

  @Transactional
  public PrivacyRequestDetailResponse request(long operatorId, long requestId) {
    RequestRow row = repository.request(requestId).orElseThrow(OpsPrivacyOpsService::notFound);
    List<PrivacyRequestEventItem> events =
        repository.events(requestId).stream()
            .map(
                event ->
                    new PrivacyRequestEventItem(
                        event.id(),
                        event.eventType(),
                        event.previousStatus(),
                        event.newStatus(),
                        event.actionDetail(),
                        event.actorOperatorName(),
                        toDisplayOffset(event.occurredAt())))
            .toList();
    PrivacyRequestStatus status = PrivacyRequestStatus.valueOf(row.status());
    List<String> allowed =
        Arrays.stream(Action.values())
            .filter(action -> PrivacyRequestProcessService.allowedFrom(action).contains(status))
            .map(Enum::name)
            .toList();
    auditLog.insert(
        operatorId, "PRIVACY_REQUEST_VIEWED", "PRIVACY_REQUEST", requestId, null, now());
    return new PrivacyRequestDetailResponse(
        row.id(),
        row.officeId(),
        row.requestType(),
        row.identityStatus(),
        row.status(),
        toDisplayOffset(row.receivedAt()),
        row.legalBasis(),
        row.requestScope(),
        toDisplayOffset(row.legalDueAt()),
        toDisplayOffset(row.internalDueAt()),
        dueState(row, clock.instant()),
        row.decisionReason(),
        row.completionEvidence(),
        row.assignedOperatorId(),
        row.assignedOperatorName(),
        toDisplayOffset(row.completedAt()),
        events,
        (long) events.size(),
        row.handlerType(),
        allowed);
  }

  @Transactional
  public PrivacyRequestProcessResponse process(
      long operatorId, long requestId, PrivacyRequestProcessRequest body) {
    Action action = Action.valueOf(body.action());
    String note = blankToNull(body.note());
    String evidence = blankToNull(body.evidenceReference());
    if ((action == Action.RESTRICT || action == Action.REJECT) && note == null) {
      throw OpsQueryParams.invalid("note", FieldErrorCode.REQUIRED, "제한·거절에는 결정 근거가 필요합니다.");
    }
    if (action == Action.COMPLETE && evidence == null) {
      throw OpsQueryParams.invalid(
          "evidenceReference", FieldErrorCode.REQUIRED, "완료에는 완료 증빙 위치가 필요합니다.");
    }
    requireActive(operatorId, OperatorRole.PRIVACY);
    IdentityStatus identity =
        body.identityStatus() == null ? null : IdentityStatus.valueOf(body.identityStatus());
    Result result =
        processService.process(
            requestId, operatorId, new Command(action, note, evidence, identity));
    auditLog.insert(
        operatorId,
        "PRIVACY_REQUEST_PROCESSED",
        "PRIVACY_REQUEST",
        requestId,
        action.name() + " → " + result.status().name(),
        result.at());
    return new PrivacyRequestProcessResponse(
        result.requestId(), result.eventId(), result.status().name(), toDisplayOffset(result.at()));
  }

  @Transactional(readOnly = true)
  public PageResponse<ReturnGrantItem> returnGrants(
      Long officeId, Boolean activeOnly, PageQuery page) {
    Long office = OpsQueryParams.positiveId("officeId", officeId);
    boolean active = Boolean.TRUE.equals(activeOnly);
    Instant now = clock.instant();
    List<ReturnGrantItem> items =
        repository
            .returnGrants(office, active, now, page.size(), (long) page.page() * page.size())
            .stream()
            .map(
                row ->
                    new ReturnGrantItem(
                        row.id(),
                        row.officeId(),
                        row.grantedToUserId(),
                        toDisplayOffset(row.grantedAt()),
                        toDisplayOffset(row.expiresAt()),
                        toDisplayOffset(row.endedAt()),
                        row.replacedByGrantId()))
            .toList();
    return PageResponse.of(
        items, page.page(), page.size(), repository.countReturnGrants(office, active, now));
  }

  @Transactional(readOnly = true)
  public PageResponse<ArchiveGrantItem> archiveGrants(
      Long officeId, Boolean activeOnly, PageQuery page) {
    Long office = OpsQueryParams.positiveId("officeId", officeId);
    boolean active = Boolean.TRUE.equals(activeOnly);
    Instant now = clock.instant();
    List<ArchiveGrantItem> items =
        repository
            .archiveGrants(office, active, now, page.size(), (long) page.page() * page.size())
            .stream()
            .map(
                row ->
                    new ArchiveGrantItem(
                        row.id(),
                        row.officeId(),
                        row.grantedToUserId(),
                        row.contractReference(),
                        toDisplayOffset(row.grantedAt()),
                        toDisplayOffset(row.expiresAt()),
                        toDisplayOffset(row.endedAt()),
                        row.replacedByGrantId()))
            .toList();
    return PageResponse.of(
        items, page.page(), page.size(), repository.countArchiveGrants(office, active, now));
  }

  @Transactional
  public ReturnGrantTransferResponse transferReturnGrant(
      long operatorId, long grantId, ReturnGrantTransferRequest body, HttpSession session) {
    GrantRow current =
        repository.lockReturnGrant(grantId).orElseThrow(OpsPrivacyOpsService::notFound);
    stepUp.require(session);
    requireActive(operatorId, OperatorRole.PRIVACY);
    Instant now = now();
    requireTransferable(current, now);
    requireNewGrantee(current, body.newGranteeUserId());
    advisoryLock.lockForTransaction("billing:return-grant:" + current.officeId());
    Instant expiresAt = now.plus(RETURN_GRANT_VALIDITY);
    long newId =
        repository.insertReturnGrant(
            new NewGrant(
                current.officeId(),
                body.newGranteeUserId(),
                null,
                now,
                expiresAt,
                body.identityEvidenceReference().strip(),
                body.registrationRelationReference().strip()));
    String reason = body.reason().strip();
    repository.revokeReturnGrant(grantId, "권한자 변경: " + reason, newId, now);
    auditLog.insert(
        operatorId,
        "RETURN_GRANT_TRANSFERRED",
        "RETURN_GRANT",
        grantId,
        transferDetail(
            newId, body.identityEvidenceReference(), body.registrationRelationReference(), reason),
        now);
    return new ReturnGrantTransferResponse(
        grantId,
        toDisplayOffset(now),
        newId,
        body.newGranteeUserId(),
        toDisplayOffset(now),
        toDisplayOffset(expiresAt));
  }

  @Transactional
  public ArchiveGrantTransferResponse transferArchiveGrant(
      long operatorId, long grantId, ArchiveGrantTransferRequest body, HttpSession session) {
    GrantRow current =
        repository.lockArchiveGrant(grantId).orElseThrow(OpsPrivacyOpsService::notFound);
    stepUp.require(session);
    requireActive(operatorId, OperatorRole.PRIVACY);
    Instant now = now();
    requireTransferable(current, now);
    requireNewGrantee(current, body.newGranteeUserId());
    long newId =
        repository.insertArchiveGrant(
            new NewGrant(
                current.officeId(),
                body.newGranteeUserId(),
                body.contractReference().strip(),
                now,
                current.expiresAt(),
                body.identityEvidenceReference().strip(),
                body.registrationRelationReference().strip()));
    String reason = body.reason().strip();
    repository.terminateArchiveGrant(grantId, "권한자 변경: " + reason, newId, now);
    auditLog.insert(
        operatorId,
        "ARCHIVE_GRANT_TRANSFERRED",
        "ARCHIVE_GRANT",
        grantId,
        transferDetail(
                newId,
                body.identityEvidenceReference(),
                body.registrationRelationReference(),
                reason)
            + ", 보관계약 "
            + body.contractReference().strip(),
        now);
    return new ArchiveGrantTransferResponse(
        grantId,
        toDisplayOffset(now),
        newId,
        body.newGranteeUserId(),
        toDisplayOffset(now),
        toDisplayOffset(current.expiresAt()));
  }

  static String dueState(RequestRow row, Instant now) {
    if (CLOSED.contains(row.status())) {
      return null;
    }
    if (now.isBefore(row.internalDueAt())) {
      return "ON_TRACK";
    }
    return now.isBefore(row.legalDueAt()) ? "APPROACHING" : "OVERDUE";
  }

  private PrivacyRequestItem item(RequestRow row, Instant now) {
    return new PrivacyRequestItem(
        row.id(),
        row.officeId(),
        row.requestType(),
        row.identityStatus(),
        row.status(),
        toDisplayOffset(row.receivedAt()),
        row.legalBasis(),
        row.requestScope(),
        toDisplayOffset(row.legalDueAt()),
        toDisplayOffset(row.internalDueAt()),
        dueState(row, now),
        row.decisionReason(),
        row.assignedOperatorId(),
        row.assignedOperatorName(),
        toDisplayOffset(row.completedAt()),
        row.handlerType());
  }

  private static void requireTransferable(GrantRow current, Instant now) {
    if (current.endedAt() != null
        || (current.expiresAt() != null && !now.isBefore(current.expiresAt()))) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
  }

  private void requireNewGrantee(GrantRow current, long newGranteeUserId) {
    if (newGranteeUserId == current.grantedToUserId()) {
      throw OpsQueryParams.invalid(
          "newGranteeUserId", FieldErrorCode.NOT_ALLOWED, "현재 권한자와 다른 회원이어야 합니다.");
    }
    if (!repository.activeUser(newGranteeUserId)) {
      throw OpsQueryParams.invalid("newGranteeUserId", FieldErrorCode.NOT_ALLOWED, "활성 회원이어야 합니다.");
    }
  }

  private void requireActive(long operatorId, OperatorRole role) {
    if (!accounts.isActiveWithRole(operatorId, role)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
  }

  private static String transferDetail(
      long newId, String identity, String relation, String reason) {
    return "새 권한 "
        + newId
        + ", 본인확인 "
        + identity.strip()
        + ", 등록관계 "
        + relation.strip()
        + ", 사유 "
        + reason;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static BusinessException notFound() {
    return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
