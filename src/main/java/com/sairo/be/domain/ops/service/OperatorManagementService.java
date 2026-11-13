package com.sairo.be.domain.ops.service;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.ops.dto.request.OpsConsoleRequests.OperatorDisableRequest;
import com.sairo.be.domain.ops.dto.request.OpsConsoleRequests.OperatorInvitationCreateRequest;
import com.sairo.be.domain.ops.dto.request.OpsConsoleRequests.OperatorRoleAssignRequest;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OperatorDisableResponse;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OperatorEnableResponse;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OperatorInvitationCancelResponse;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OperatorInvitationCreateResponse;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OperatorInvitationItem;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OperatorItem;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OperatorOption;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OpsAuditLogItem;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OpsOperatorOptionListResponse;
import com.sairo.be.domain.ops.entity.InvitationStatus;
import com.sairo.be.domain.ops.entity.OperatorStatus;
import com.sairo.be.domain.ops.repository.OperatorAccountRepository;
import com.sairo.be.domain.ops.repository.OperatorInvitationRepository;
import com.sairo.be.domain.ops.repository.OperatorInvitationRepository.Listing;
import com.sairo.be.domain.ops.repository.OpsApprovalRequestRepository;
import com.sairo.be.domain.ops.repository.OpsAuditLogRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.port.MailSender;
import com.sairo.be.global.port.MailSender.MailMessage;
import com.sairo.be.global.security.OperatorPrincipal;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.security.OperatorStepUpState;
import com.sairo.be.global.security.PrincipalSessions;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.AfterCommit;
import com.sairo.be.global.support.IfMatchVersion;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import com.sairo.be.global.support.TokenGenerator;
import jakarta.servlet.http.HttpSession;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class OperatorManagementService {

  static final Duration INVITATION_VALIDITY = Duration.ofHours(72);
  private static final String SECURITY_ADMIN_LOCK = "ops:security-admin";

  private final OperatorAccountRepository accounts;
  private final OperatorInvitationRepository invitations;
  private final OpsApprovalRequestRepository approvals;
  private final OpsAuditLogRepository auditLog;
  private final OperatorStepUpState stepUp;
  private final PrincipalSessions sessions;
  private final AdvisoryLock advisoryLock;
  private final TokenGenerator tokenGenerator;
  private final MailSender mailSender;
  private final Clock clock;
  private final String frontendBaseUrl;

  public OperatorManagementService(
      OperatorAccountRepository accounts,
      OperatorInvitationRepository invitations,
      OpsApprovalRequestRepository approvals,
      OpsAuditLogRepository auditLog,
      OperatorStepUpState stepUp,
      PrincipalSessions sessions,
      AdvisoryLock advisoryLock,
      TokenGenerator tokenGenerator,
      MailSender mailSender,
      Clock clock,
      @Value("${app.frontend-base-url:http://localhost:3000}") String frontendBaseUrl) {
    this.accounts = accounts;
    this.invitations = invitations;
    this.approvals = approvals;
    this.auditLog = auditLog;
    this.stepUp = stepUp;
    this.sessions = sessions;
    this.advisoryLock = advisoryLock;
    this.tokenGenerator = tokenGenerator;
    this.mailSender = mailSender;
    this.clock = clock;
    this.frontendBaseUrl = frontendBaseUrl;
  }

  // ---- 운영자 ----

  @Transactional(readOnly = true)
  public PageResponse<OperatorItem> operators(PageQuery page) {
    List<OperatorItem> items =
        accounts.list(page.size(), (long) page.page() * page.size()).stream()
            .map(summary -> toItem(summary, accounts.roles(summary.id())))
            .toList();
    return PageResponse.of(items, page.page(), page.size(), accounts.count());
  }

  @Transactional(readOnly = true)
  public OpsOperatorOptionListResponse options() {
    return new OpsOperatorOptionListResponse(
        accounts.activeOptions().stream()
            .map(option -> new OperatorOption(option.id(), option.name(), option.roles()))
            .toList());
  }

  @Transactional
  public OperatorItem assignRoles(
      long actorId, long operatorId, OperatorRoleAssignRequest request, HttpSession session) {
    stepUp.require(session);
    Set<OperatorRole> wanted = EnumSet.noneOf(OperatorRole.class);
    request.roles().forEach(role -> wanted.add(OperatorRole.valueOf(role)));
    advisoryLock.lockForTransaction(SECURITY_ADMIN_LOCK);
    var account =
        accounts
            .lockById(operatorId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    List<OperatorRole> current = accounts.roles(operatorId);
    boolean losesSecurityAdmin =
        current.contains(OperatorRole.SECURITY_ADMIN)
            && !wanted.contains(OperatorRole.SECURITY_ADMIN);
    if (losesSecurityAdmin
        && account.status() == OperatorStatus.ACTIVE
        && accounts.countOtherActiveSecurityAdmins(operatorId) == 0) {
      throw new BusinessException(ErrorCode.LAST_SECURITY_ADMIN);
    }
    Instant now = clock.instant();
    for (OperatorRole role : current) {
      if (!wanted.contains(role)) {
        accounts.deleteRole(operatorId, role);
        auditLog.insert(actorId, "OPERATOR_ROLE_REVOKED", "OPERATOR", operatorId, role.name(), now);
      }
    }
    for (OperatorRole role : wanted) {
      if (!current.contains(role)) {
        accounts.insertRole(operatorId, role, now);
        auditLog.insert(actorId, "OPERATOR_ROLE_GRANTED", "OPERATOR", operatorId, role.name(), now);
      }
    }
    List<OperatorRole> updated = accounts.roles(operatorId);
    if (current.contains(OperatorRole.PRIVACY) && !updated.contains(OperatorRole.PRIVACY)) {
      approvals.invalidateUnexecutedFor(operatorId, now);
    }
    OperatorPrincipal refreshed = new OperatorPrincipal(operatorId, account.name(), updated);
    AfterCommit.run(() -> sessions.replaceOperatorPrincipal(refreshed));
    return toItem(accounts.summary(operatorId).orElseThrow(), updated);
  }

  @Transactional
  public OperatorDisableResponse disable(
      long actorId, long operatorId, OperatorDisableRequest request, HttpSession session) {
    stepUp.require(session);
    if (actorId == operatorId) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    advisoryLock.lockForTransaction(SECURITY_ADMIN_LOCK);
    var account =
        accounts
            .lockById(operatorId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (account.status() != OperatorStatus.ACTIVE) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    if (accounts.roles(operatorId).contains(OperatorRole.SECURITY_ADMIN)
        && accounts.countOtherActiveSecurityAdmins(operatorId) == 0) {
      throw new BusinessException(ErrorCode.LAST_SECURITY_ADMIN);
    }
    Instant now = clock.instant();
    accounts.updateStatus(operatorId, OperatorStatus.DISABLED);
    int invalidated = approvals.invalidateUnexecutedFor(operatorId, now);
    auditLog.insert(
        actorId,
        "OPERATOR_DISABLED",
        "OPERATOR",
        operatorId,
        "reason=" + request.reason().strip(),
        now);
    String principalName = PrincipalSessions.operatorPrincipalName(operatorId);
    int sessionCount = sessions.list(principalName).size();
    AfterCommit.run(() -> sessions.terminateAll(principalName));
    return new OperatorDisableResponse(
        operatorId, OperatorStatus.DISABLED.name(), sessionCount, invalidated);
  }

  @Transactional
  public OperatorEnableResponse enable(long actorId, long operatorId, HttpSession session) {
    stepUp.require(session);
    var account =
        accounts
            .lockById(operatorId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (account.status() != OperatorStatus.DISABLED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant now = clock.instant();
    accounts.updateStatus(operatorId, OperatorStatus.ACTIVE);
    auditLog.insert(actorId, "OPERATOR_ENABLED", "OPERATOR", operatorId, null, now);
    return new OperatorEnableResponse(operatorId, OperatorStatus.ACTIVE.name());
  }

  // ---- 초대 ----

  @Transactional
  public OperatorInvitationCreateResponse createInvitation(
      long actorId, OperatorInvitationCreateRequest request, HttpSession session) {
    stepUp.require(session);
    String email = request.inviteeEmail().strip().toLowerCase(Locale.ROOT);
    String name = request.inviteeName().strip();
    EnumSet<OperatorRole> distinct = EnumSet.noneOf(OperatorRole.class);
    request.roles().forEach(role -> distinct.add(OperatorRole.valueOf(role)));
    List<OperatorRole> roles = new ArrayList<>(distinct);
    advisoryLock.lockForTransaction("ops:invitation:" + email);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    for (Long pendingId : invitations.lockPendingByEmail(email)) {
      Listing pending = invitations.listing(pendingId, now).orElseThrow();
      if (pending.status() == InvitationStatus.PENDING) {
        throw new BusinessException(ErrorCode.INVITATION_ALREADY_PENDING);
      }
      invitations.markExpired(pendingId, now);
      auditLog.insert(
          actorId, "OPERATOR_INVITATION_EXPIRED", "OPERATOR_INVITATION", pendingId, null, now);
    }
    String token = tokenGenerator.randomToken();
    Instant expiresAt = now.plus(INVITATION_VALIDITY);
    long id =
        invitations.insert(
            name, email, OperatorInvitationAuthService.sha256(token), actorId, now, expiresAt);
    roles.forEach(role -> invitations.insertRole(id, role));
    auditLog.insert(
        actorId,
        "OPERATOR_INVITATION_CREATED",
        "OPERATOR_INVITATION",
        id,
        "roles=" + String.join(",", roles.stream().map(Enum::name).toList()),
        now);
    String link =
        frontendBaseUrl
            + "/ops/invitations/accept?token="
            + URLEncoder.encode(token, StandardCharsets.UTF_8);
    AfterCommit.run(
        () ->
            mailSender.send(
                new MailMessage(
                    email,
                    "[사이로] 운영자 초대",
                    name
                        + "님, 사이로 운영 콘솔 운영자로 초대되었습니다.\n아래 링크에서 72시간 안에 카카오 인증과"
                        + " 2차 인증 등록을 마치면 계정이 만들어집니다.\n"
                        + link)));
    return new OperatorInvitationCreateResponse(
        id,
        name,
        email,
        roles.stream().map(Enum::name).toList(),
        InvitationStatus.PENDING.name(),
        toDisplayOffset(expiresAt),
        toDisplayOffset(now),
        toDisplayOffset(now),
        1);
  }

  @Transactional(readOnly = true)
  public PageResponse<OperatorInvitationItem> invitations(String status, PageQuery page) {
    InvitationStatus filter = null;
    if (status != null && !status.isBlank()) {
      try {
        filter = InvitationStatus.valueOf(status.strip());
      } catch (IllegalArgumentException e) {
        throw OpsQueryParams.invalid("status", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 status 값입니다.");
      }
    }
    Instant now = clock.instant();
    List<OperatorInvitationItem> items =
        invitations.list(filter, now, page.size(), (long) page.page() * page.size()).stream()
            .map(
                row ->
                    new OperatorInvitationItem(
                        row.id(),
                        row.inviteeName(),
                        row.inviteeEmail(),
                        invitations.roles(row.id()).stream().map(Enum::name).toList(),
                        row.status().name(),
                        toDisplayOffset(row.expiresAt()),
                        row.invitedByOperatorId(),
                        row.invitedByName(),
                        row.acceptedOperatorId(),
                        toDisplayOffset(row.acceptedAt()),
                        toDisplayOffset(row.cancelledAt()),
                        toDisplayOffset(row.createdAt()),
                        row.version()))
            .toList();
    return PageResponse.of(items, page.page(), page.size(), invitations.count(filter, now));
  }

  @Transactional
  public OperatorInvitationCancelResponse cancelInvitation(
      long actorId, long invitationId, String ifMatch, HttpSession session) {
    stepUp.require(session);
    long expected = IfMatchVersion.parse(ifMatch);
    invitations
        .lockById(invitationId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    Instant now = clock.instant();
    Listing invitation = invitations.listing(invitationId, now).orElseThrow();
    if (invitation.status() != InvitationStatus.PENDING) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    if (invitation.version() != expected) {
      throw BusinessException.versionConflict(invitation.version());
    }
    invitations.markCancelled(invitationId, actorId, now);
    auditLog.insert(
        actorId, "OPERATOR_INVITATION_CANCELLED", "OPERATOR_INVITATION", invitationId, null, now);
    Listing cancelled = invitations.listing(invitationId, now).orElseThrow();
    return new OperatorInvitationCancelResponse(
        invitationId,
        cancelled.status().name(),
        toDisplayOffset(cancelled.cancelledAt()),
        toDisplayOffset(cancelled.updatedAt()),
        cancelled.version());
  }

  // ---- 감사 이력 ----

  @Transactional(readOnly = true)
  public PageResponse<OpsAuditLogItem> auditLog(
      String actionType,
      Long operatorId,
      String targetType,
      Long targetId,
      String from,
      String to,
      PageQuery page) {
    String action = OpsQueryParams.code("actionType", actionType);
    Instant fromAt = OpsQueryParams.instant("from", from);
    Instant toAt = OpsQueryParams.instant("to", to);
    if (fromAt != null && toAt != null && !fromAt.isBefore(toAt)) {
      throw OpsQueryParams.invalid("to", FieldErrorCode.OUT_OF_RANGE, "to는 from보다 뒤여야 합니다.");
    }
    var filter =
        new OpsAuditLogRepository.Filter(
            action == null ? List.of() : List.of(action),
            OpsQueryParams.positiveId("operatorId", operatorId),
            OpsQueryParams.code("targetType", targetType),
            OpsQueryParams.positiveId("targetId", targetId),
            fromAt,
            toAt);
    List<OpsAuditLogItem> items =
        auditLog.search(filter, page.size(), (long) page.page() * page.size()).stream()
            .map(
                row ->
                    new OpsAuditLogItem(
                        row.id(),
                        row.operatorId(),
                        row.operatorName(),
                        row.actionType(),
                        row.targetType(),
                        row.targetId(),
                        row.detail(),
                        toDisplayOffset(row.createdAt())))
            .toList();
    return PageResponse.of(items, page.page(), page.size(), auditLog.count(filter));
  }

  private static OperatorItem toItem(
      OperatorAccountRepository.Summary summary, List<OperatorRole> roles) {
    return new OperatorItem(
        summary.id(),
        summary.name(),
        summary.status().name(),
        roles.stream().map(Enum::name).toList(),
        toDisplayOffset(summary.createdAt()));
  }
}
