package com.sairo.be.domain.ops.service;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.ops.dto.request.OpsConsoleRequests.MemberEmergencySuspendRequest;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.CurrentMembership;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.MemberEmergencySuspendResponse;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.MemberSession;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OperatorMemberDetailResponse;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OperatorMemberItem;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.PendingRequestSummary;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.SecurityEvent;
import com.sairo.be.domain.ops.repository.OpsApprovalRequestRepository;
import com.sairo.be.domain.ops.repository.OpsAuditLogRepository;
import com.sairo.be.domain.ops.repository.OpsConsoleQueryRepository;
import com.sairo.be.domain.ops.repository.OpsConsoleQueryRepository.LoginRow;
import com.sairo.be.domain.ops.repository.OpsConsoleQueryRepository.MemberFilter;
import com.sairo.be.domain.ops.repository.OpsConsoleQueryRepository.UserRow;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.port.NotificationPublisher;
import com.sairo.be.global.port.NotificationPublisher.Notice;
import com.sairo.be.global.security.PrincipalSessions;
import com.sairo.be.global.security.PrincipalSessions.SessionInfo;
import com.sairo.be.global.support.AfterCommit;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import com.sairo.be.global.support.UserAgentSummarizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class OpsMemberService {

  static final Set<String> ACCOUNT_STATUSES = Set.of("ACTIVE", "WITHDRAWN");
  private static final int SECURITY_EVENT_LIMIT = 20;
  private static final int LOGIN_LOOKUP_LIMIT = 50;
  private static final Duration LOGIN_MATCH_WINDOW = Duration.ofMinutes(1);
  private static final String SUSPEND_NOTICE =
      "구성원 계정의 접속이 보안 사유로 중단되었습니다. 자세한 내용은 고객지원으로 문의해 주세요.";

  private final OpsConsoleQueryRepository queries;
  private final OpsApprovalRequestRepository approvals;
  private final OpsAuditLogRepository auditLog;
  private final PrincipalSessions sessions;
  private final NotificationPublisher notifications;
  private final Clock clock;

  public OpsMemberService(
      OpsConsoleQueryRepository queries,
      OpsApprovalRequestRepository approvals,
      OpsAuditLogRepository auditLog,
      PrincipalSessions sessions,
      NotificationPublisher notifications,
      Clock clock) {
    this.queries = queries;
    this.approvals = approvals;
    this.auditLog = auditLog;
    this.sessions = sessions;
    this.notifications = notifications;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PageResponse<OperatorMemberItem> list(
      String keyword,
      String accountStatus,
      String registrationStatus,
      String subscriptionStatus,
      PageQuery page) {
    MemberFilter filter =
        new MemberFilter(
            OpsQueryParams.keyword(keyword),
            OpsQueryParams.oneOf("accountStatus", accountStatus, ACCOUNT_STATUSES),
            OpsQueryParams.oneOf(
                "registrationStatus", registrationStatus, OpsHomeService.REGISTRATION_STATUSES),
            OpsQueryParams.oneOf(
                "subscriptionStatus", subscriptionStatus, OpsHomeService.SUBSCRIPTION_STATUSES));
    List<OperatorMemberItem> items =
        queries.users(filter, page.size(), (long) page.page() * page.size()).stream()
            .map(
                row ->
                    new OperatorMemberItem(
                        row.userId(),
                        row.displayName(),
                        row.accountStatus(),
                        row.officeName(),
                        toDisplayOffset(row.createdAt())))
            .toList();
    return PageResponse.of(items, page.page(), page.size(), queries.countUsers(filter));
  }

  @Transactional(readOnly = true)
  public OperatorMemberDetailResponse detail(long userId) {
    UserRow user = findUser(userId);
    Instant now = clock.instant();
    CurrentMembership membership =
        queries
            .currentMembership(userId)
            .map(row -> new CurrentMembership(row.officeId(), row.officeName(), row.role()))
            .orElse(null);
    PendingRequestSummary pending =
        queries
            .pendingRegistrationStatus(userId)
            .map(status -> new PendingRequestSummary("REGISTRATION", status))
            .or(
                () ->
                    queries
                        .pendingMembershipStatus(userId)
                        .map(status -> new PendingRequestSummary("MEMBERSHIP", status)))
            .orElse(null);
    List<String> loginMethods = new ArrayList<>();
    if (user.hasKakao()) {
      loginMethods.add("KAKAO");
    }
    if (user.hasEmail()) {
      loginMethods.add("EMAIL");
    }
    List<LoginRow> logins = queries.recentLogins(userId, LOGIN_LOOKUP_LIMIT);
    List<MemberSession> memberSessions =
        sessions.list(PrincipalSessions.memberPrincipalName(userId)).stream()
            .map(
                info ->
                    new MemberSession(
                        info.opaqueId(),
                        toDisplayOffset(info.createdAt()),
                        toDisplayOffset(info.lastSeenAt()),
                        deviceSummary(info, logins)))
            .toList();
    List<SecurityEvent> events =
        queries.suspensions(userId, SECURITY_EVENT_LIMIT).stream()
            .map(
                row ->
                    new SecurityEvent(
                        "EMERGENCY_SUSPENDED",
                        toDisplayOffset(row.suspendedAt()),
                        row.scope(),
                        row.reason(),
                        toDisplayOffset(row.reviewAt()),
                        row.operatorName()))
            .toList();
    Instant locked = user.loginLockedUntil();
    return new OperatorMemberDetailResponse(
        user.id(),
        user.name(),
        user.accountStatus(),
        toDisplayOffset(user.withdrawnAt()),
        membership,
        pending,
        loginMethods,
        logins.isEmpty() ? null : toDisplayOffset(logins.get(0).createdAt()),
        locked != null && locked.isAfter(now) ? toDisplayOffset(locked) : null,
        memberSessions,
        events);
  }

  /** 세션 시작 직전·직후 1분 안의 로그인 기록으로 기기를 요약한다. 없으면 "알 수 없음"이다. */
  private static String deviceSummary(SessionInfo info, List<LoginRow> logins) {
    for (LoginRow login : logins) {
      Duration gap = Duration.between(login.createdAt(), info.createdAt()).abs();
      if (gap.compareTo(LOGIN_MATCH_WINDOW) <= 0) {
        return truncate(UserAgentSummarizer.summarize(login.userAgent()));
      }
    }
    return UserAgentSummarizer.summarize(null);
  }

  private static String truncate(String value) {
    return value.length() > 100 ? value.substring(0, 100) : value;
  }

  @Transactional
  public MemberEmergencySuspendResponse emergencySuspend(
      long operatorId, long userId, MemberEmergencySuspendRequest request) {
    boolean specific = request.scope().equals("SPECIFIC_SESSION");
    String sessionId = request.sessionId() == null ? null : request.sessionId().strip();
    if (sessionId != null && sessionId.isEmpty()) {
      sessionId = null;
    }
    if (specific && sessionId == null) {
      throw OpsQueryParams.invalid(
          "sessionId", FieldErrorCode.REQUIRED, "SPECIFIC_SESSION에는 sessionId가 필요합니다.");
    }
    if (!specific && sessionId != null) {
      throw OpsQueryParams.invalid(
          "sessionId", FieldErrorCode.NOT_ALLOWED, "ALL_SESSIONS에는 sessionId를 보낼 수 없습니다.");
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Instant reviewAt = request.reviewAt().toInstant();
    if (!reviewAt.isAfter(now)) {
      throw OpsQueryParams.invalid(
          "reviewAt", FieldErrorCode.OUT_OF_RANGE, "재검토 예정 시각은 현재보다 뒤여야 합니다.");
    }
    UserRow user = findUser(userId);
    if (!user.accountStatus().equals("ACTIVE")) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    String principalName = PrincipalSessions.memberPrincipalName(userId);
    if (specific) {
      String target = sessionId;
      boolean known =
          sessions.list(principalName).stream().anyMatch(info -> info.opaqueId().equals(target));
      if (!known) {
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
      }
    }
    String reason = request.reason().strip();
    queries.insertSuspension(userId, request.scope(), sessionId, reason, now, reviewAt, operatorId);
    approvals.invalidateUnexecutedForTarget("MEMBER", userId, now);
    auditLog.insert(
        operatorId,
        "MEMBER_EMERGENCY_SUSPENDED",
        "MEMBER",
        userId,
        "scope=" + request.scope(),
        now);
    String target = sessionId;
    AfterCommit.run(
        () -> {
          if (specific) {
            sessions.terminateByOpaqueId(principalName, target);
          } else {
            sessions.terminateAll(principalName);
          }
        });
    queries
        .currentMembership(userId)
        .ifPresent(
            membership ->
                notifications.publishToOwners(
                    membership.officeId(),
                    new Notice(
                        membership.officeId(),
                        null,
                        "SECURITY_ALERT",
                        SUSPEND_NOTICE,
                        "NONE",
                        null)));
    return new MemberEmergencySuspendResponse(
        userId, request.scope(), toDisplayOffset(now), toDisplayOffset(reviewAt));
  }

  private UserRow findUser(long userId) {
    return queries
        .user(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }
}
