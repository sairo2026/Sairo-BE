package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.visit.dto.response.ApplyGrantResponses.ApplyGrantResponse;
import com.sairo.be.domain.visit.dto.response.ApplyGrantResponses.ApplyGrantRevokeResponse;
import com.sairo.be.domain.visit.dto.response.ApplyGrantResponses.ReceivedApplyGrantItem;
import com.sairo.be.domain.visit.entity.ApplyGrantState;
import com.sairo.be.domain.visit.entity.PropertyAccessApproval;
import com.sairo.be.domain.visit.entity.RequestNoticeType;
import com.sairo.be.domain.visit.entity.RequestStatus;
import com.sairo.be.domain.visit.entity.RequestStatusSource;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.entity.VisitRequest;
import com.sairo.be.domain.visit.entity.VisitRequestNotice;
import com.sairo.be.domain.visit.repository.ApplyGrantRepository;
import com.sairo.be.domain.visit.repository.ApplyGrantRepository.GrantRow;
import com.sairo.be.domain.visit.repository.ApplyGrantRepository.PendingRequest;
import com.sairo.be.domain.visit.repository.ApplyGrantRepository.ReceivedRow;
import com.sairo.be.domain.visit.repository.MembershipNameRepository;
import com.sairo.be.domain.visit.repository.VisitCoordinationRepository;
import com.sairo.be.domain.visit.repository.VisitRequestNoticeRepository;
import com.sairo.be.domain.visit.repository.VisitRequestRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.port.OfficeDirectory;
import com.sairo.be.global.port.OfficeWriteRestriction;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** 다른 사무소 신청 허용의 부여·조회·회수와 허용받은 매물 목록이다(VISIT-20·21). */
@Profile("!migrate")
@Service
public class ApplyGrantService {

  static final String REASON_REVOKED = "GRANT_REVOKED";
  static final String REASON_EXPIRED = "GRANT_EXPIRED";
  static final String REASON_PROPERTY_ARCHIVED = "PROPERTY_ARCHIVED";
  private static final Set<String> CREATE_KEYS =
      Set.of("granteeOfficeId", "granteeMembershipId", "expiresAt");
  private static final Set<String> REVOKE_KEYS = Set.of("reason");
  private static final int REASON_MAX = 500;
  private static final int KEYWORD_MAX = 100;

  private final ApprovalAccess access;
  private final ApplyGrantRepository grantRepository;
  private final OfficeDirectory officeDirectory;
  private final OfficeWriteRestriction writeRestriction;
  private final MembershipNameRepository names;
  private final VisitCoordinationRepository coordinationRepository;
  private final VisitRequestRepository requestRepository;
  private final VisitRequestStatusService requestStatus;
  private final VisitRequestNoticeRepository noticeRepository;
  private final Clock clock;

  public ApplyGrantService(
      ApprovalAccess access,
      ApplyGrantRepository grantRepository,
      OfficeDirectory officeDirectory,
      OfficeWriteRestriction writeRestriction,
      MembershipNameRepository names,
      VisitCoordinationRepository coordinationRepository,
      VisitRequestRepository requestRepository,
      VisitRequestStatusService requestStatus,
      VisitRequestNoticeRepository noticeRepository,
      Clock clock) {
    this.access = access;
    this.grantRepository = grantRepository;
    this.officeDirectory = officeDirectory;
    this.writeRestriction = writeRestriction;
    this.names = names;
    this.coordinationRepository = coordinationRepository;
    this.requestRepository = requestRepository;
    this.requestStatus = requestStatus;
    this.noticeRepository = noticeRepository;
    this.clock = clock;
  }

  @Transactional
  public ApplyGrantResponse create(
      StaffPrincipal principal, Long propertyId, Long approvalId, JsonNode body) {
    access.requireAgentOrOwner(principal);
    CustomerInput.requireObject(body, CREATE_KEYS);
    Long granteeOfficeId = CustomerInput.requiredLong(body, "granteeOfficeId");
    Long granteeMembershipId = CustomerInput.optionalLong(body, "granteeMembershipId");
    OffsetDateTime expiresInput = CustomerInput.optionalDateTime(body, "expiresAt");
    PropertyAccessApproval approval = access.lock(principal, propertyId, approvalId);
    if (writeRestriction.restricted(principal.officeId())) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    Instant now = now();
    if (!approval.isPrimary() || approval.isRevoked() || !approval.getValidUntil().isAfter(now)) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant expiresAt =
        expiresInput == null ? null : expiresInput.toInstant().truncatedTo(ChronoUnit.MICROS);
    if (expiresAt != null
        && (!expiresAt.isAfter(now) || expiresAt.isAfter(approval.getValidUntil()))) {
      throw CustomerInput.invalid(
          "expiresAt", FieldErrorCode.OUT_OF_RANGE, "현재 이후이고 출입승인 유효 종료 이하여야 합니다.");
    }
    if (granteeOfficeId.equals(principal.officeId())
        || !grantRepository.isVerifiedOffice(granteeOfficeId)
        || (granteeMembershipId != null
            && !grantRepository.isApprovedBroker(granteeMembershipId, granteeOfficeId))) {
      throw new BusinessException(ErrorCode.APPLY_GRANT_TARGET_INVALID);
    }
    for (GrantRow existing : grantRepository.unrevokedFor(approvalId, granteeOfficeId, now)) {
      boolean sameTarget =
          java.util.Objects.equals(existing.granteeMembershipId(), granteeMembershipId);
      if (ApplyGrantState.ACTIVE.name().equals(existing.state())) {
        if (sameTarget || existing.granteeMembershipId() == null) {
          throw new BusinessException(ErrorCode.APPLY_GRANT_DUPLICATE_ACTIVE);
        }
      } else if (sameTarget) {
        grantRepository.revokeBySystem(existing.id(), "만료된 허용을 새 허용으로 대체", now);
        grantRepository.recordEvent(existing.id(), "EXPIRED", null, REASON_EXPIRED, 0, now);
      }
    }
    Long grantId =
        grantRepository.insert(
            approvalId,
            propertyId,
            principal.officeId(),
            granteeOfficeId,
            granteeMembershipId,
            principal.membershipId(),
            now,
            expiresAt);
    grantRepository.recordEvent(grantId, "GRANTED", principal.membershipId(), null, 0, now);
    return response(grantRepository.find(grantId, approvalId, now).orElseThrow());
  }

  @Transactional(readOnly = true)
  public PageResponse<ApplyGrantResponse> list(
      StaffPrincipal principal,
      Long propertyId,
      Long approvalId,
      String stateFilter,
      PageQuery pageQuery) {
    access.requireAgentOrOwner(principal);
    access.view(principal, propertyId, approvalId);
    Set<String> states = parseStates(stateFilter);
    Instant now = clock.instant();
    long total = grantRepository.countByApproval(approvalId, states, now);
    List<ApplyGrantResponse> items =
        grantRepository
            .pageByApproval(
                approvalId,
                states,
                now,
                pageQuery.size(),
                (long) pageQuery.page() * pageQuery.size())
            .stream()
            .map(this::response)
            .toList();
    return PageResponse.of(items, pageQuery.page(), pageQuery.size(), total);
  }

  @Transactional
  public ApplyGrantRevokeResponse revoke(
      StaffPrincipal principal, Long propertyId, Long approvalId, Long grantId, JsonNode body) {
    access.requireAgentOrOwner(principal);
    CustomerInput.requireObject(body, REVOKE_KEYS);
    String reason = CustomerInput.requiredText(body, "reason", REASON_MAX, false);
    access.lock(principal, propertyId, approvalId);
    if (!grantRepository.lock(grantId, approvalId)) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    Instant now = now();
    GrantRow grant = grantRepository.find(grantId, approvalId, now).orElseThrow();
    if (!ApplyGrantState.ACTIVE.name().equals(grant.state())) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    grantRepository.revokeByOffice(grantId, principal.membershipId(), reason, now);
    List<Long> closed = closePending(grantId, REASON_REVOKED, now);
    grantRepository.recordEvent(
        grantId, "REVOKED", principal.membershipId(), "USER_REVOKED", closed.size(), now);
    return new ApplyGrantRevokeResponse(
        response(grantRepository.find(grantId, approvalId, now).orElseThrow()),
        closed,
        grantRepository.keptScheduleItemIds(grantId));
  }

  /**
   * 출입승인 철회를 접수했을 때 그 출입승인의 허용을 만료로 기록하고 허용 근거 신청 대기 신청을 GRANT_EXPIRED로 종결한다. 허용 행은 회수하지 않으며 상태는
   * 출입승인 철회로 EXPIRED가 된다.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int expireForRevokedApproval(Long approvalId) {
    Instant now = now();
    int closedCount = 0;
    for (Long grantId : grantRepository.unrevokedIdsByApproval(approvalId)) {
      List<Long> closed = closePending(grantId, REASON_EXPIRED, now);
      grantRepository.recordEvent(grantId, "EXPIRED", null, "APPROVAL_REVOKED", closed.size(), now);
      closedCount += closed.size();
    }
    return closedCount;
  }

  /**
   * 매물 보관 때 그 매물의 활성 허용을 시스템 회수(PROPERTY_ARCHIVED)하고 허용 근거 신청 대기 신청을 GRANT_REVOKED로 종결한다. 신청 상태 이력,
   * 신청자 알림, 조율 상태 재계산은 사무소 회수와 같은 경로를 쓴다.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int revokeForArchivedProperty(Long propertyId) {
    Instant now = now();
    List<Long> grantIds = grantRepository.activeIdsByProperty(propertyId, now);
    for (Long grantId : grantIds) {
      grantRepository.revokeBySystem(grantId, REASON_PROPERTY_ARCHIVED, now);
      List<Long> closed = closePending(grantId, REASON_REVOKED, now);
      grantRepository.recordEvent(
          grantId, "REVOKED", null, REASON_PROPERTY_ARCHIVED, closed.size(), now);
    }
    return grantIds.size();
  }

  @Transactional(readOnly = true)
  public PageResponse<ReceivedApplyGrantItem> received(
      StaffPrincipal principal, String keyword, PageQuery pageQuery) {
    access.requireAgentOrOwner(principal);
    String search = normalizeKeyword(keyword);
    Instant now = clock.instant();
    long total =
        grantRepository.countReceived(principal.officeId(), principal.membershipId(), search, now);
    List<ReceivedApplyGrantItem> items = new ArrayList<>();
    for (ReceivedRow row :
        grantRepository.pageReceived(
            principal.officeId(),
            principal.membershipId(),
            search,
            now,
            pageQuery.size(),
            (long) pageQuery.page() * pageQuery.size())) {
      items.add(
          new ReceivedApplyGrantItem(
              row.grantId(),
              row.approvalId(),
              row.propertyId(),
              row.ownerOfficeId(),
              row.ownerOfficeName(),
              row.granteeMembershipId() == null ? "OFFICE" : "MEMBER",
              row.granteeMembershipId(),
              row.propertyName(),
              row.propertyAddress() == null ? "" : row.propertyAddress(),
              row.categoryName(),
              grantRepository.activeDealTypes(row.propertyId()),
              TimeRules.toDisplayOffset(row.approvalValidUntil()),
              TimeRules.toDisplayOffset(row.grantExpiresAt()),
              row.conditionAppliesUntil() != null,
              TimeRules.toDisplayOffset(row.conditionAppliesUntil())));
    }
    return PageResponse.of(items, pageQuery.page(), pageQuery.size(), total);
  }

  private List<Long> closePending(Long grantId, String reason, Instant now) {
    List<Long> closed = new ArrayList<>();
    for (PendingRequest pending : grantRepository.pendingRequests(grantId)) {
      Optional<VisitCoordination> coordination =
          coordinationRepository.findForUpdateById(pending.coordinationId());
      Optional<VisitRequest> request = requestRepository.findForUpdate(pending.requestId());
      if (coordination.isEmpty()
          || request.isEmpty()
          || request.get().getStatus() != RequestStatus.PENDING) {
        continue;
      }
      requestStatus.close(
          request.get(),
          coordination.get(),
          RequestStatus.EXPIRED,
          RequestStatusSource.SYSTEM,
          null,
          reason);
      noticeRepository.save(
          VisitRequestNotice.of(
              pending.requestId(),
              request.get().getApplicantMembershipId(),
              RequestNoticeType.GRANT_REVOKED,
              now));
      closed.add(pending.requestId());
    }
    return closed;
  }

  private ApplyGrantResponse response(GrantRow row) {
    Set<Long> memberIds = new HashSet<>();
    memberIds.add(row.grantedByMembershipId());
    if (row.granteeMembershipId() != null) {
      memberIds.add(row.granteeMembershipId());
    }
    Map<Long, String> memberNames = names.displayNames(memberIds);
    return new ApplyGrantResponse(
        row.id(),
        row.approvalId(),
        row.propertyId(),
        row.ownerOfficeId(),
        row.granteeOfficeId(),
        officeDirectory
            .find(row.granteeOfficeId())
            .map(OfficeDirectory.OfficeSummary::name)
            .orElse(""),
        row.granteeMembershipId(),
        row.granteeMembershipId() == null
            ? null
            : memberNames.getOrDefault(row.granteeMembershipId(), ""),
        row.granteeMembershipId() == null ? "OFFICE" : "MEMBER",
        row.grantedByMembershipId(),
        memberNames.getOrDefault(row.grantedByMembershipId(), ""),
        TimeRules.toDisplayOffset(row.grantedAt()),
        TimeRules.toDisplayOffset(row.expiresAt()),
        TimeRules.toDisplayOffset(row.revokedAt()),
        row.revokeSource(),
        row.revokeReason(),
        row.state(),
        row.openRequestCount());
  }

  private static Set<String> parseStates(String raw) {
    if (raw == null || raw.isBlank()) {
      Set<String> all = new LinkedHashSet<>();
      for (ApplyGrantState state : ApplyGrantState.values()) {
        all.add(state.name());
      }
      return all;
    }
    Set<ApplyGrantState> parsed = EnumSet.noneOf(ApplyGrantState.class);
    for (String token : raw.split(",")) {
      try {
        parsed.add(ApplyGrantState.valueOf(token.strip()));
      } catch (IllegalArgumentException e) {
        throw BusinessException.withFieldErrors(
            ErrorCode.INVALID_REQUEST,
            List.of(
                new FieldErrorItem("state", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 state 값입니다.")));
      }
    }
    Set<String> names = new LinkedHashSet<>();
    parsed.forEach(state -> names.add(state.name()));
    return names;
  }

  private static String normalizeKeyword(String keyword) {
    if (keyword == null || keyword.isBlank()) {
      return null;
    }
    String trimmed = keyword.strip();
    if (trimmed.length() > KEYWORD_MAX) {
      throw BusinessException.withFieldErrors(
          ErrorCode.INVALID_REQUEST,
          List.of(new FieldErrorItem("keyword", FieldErrorCode.TOO_LONG, "100자 이하여야 합니다.")));
    }
    return trimmed;
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
