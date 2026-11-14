package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.property.service.PropertyReferenceService;
import com.sairo.be.domain.visit.dto.response.ApproverAccountResponses.ApproverLinkDecisionResponse;
import com.sairo.be.domain.visit.dto.response.ApproverAccountResponses.ApproverPortalApprovalItem;
import com.sairo.be.domain.visit.dto.response.ApproverAccountResponses.ApproverPortalApprovalListResponse;
import com.sairo.be.domain.visit.dto.response.ApproverAccountResponses.ApproverProfileResponse;
import com.sairo.be.domain.visit.dto.response.ApproverAccountResponses.ApproverSessionResponse;
import com.sairo.be.domain.visit.dto.response.ApproverVisitRequestCompareResponse.PrivacyNotice;
import com.sairo.be.domain.visit.entity.ConditionStatus;
import com.sairo.be.domain.visit.entity.ConsentGuestNoticeAck;
import com.sairo.be.domain.visit.entity.PropertyAccessApproval;
import com.sairo.be.domain.visit.entity.VisitApproverCondition;
import com.sairo.be.domain.visit.repository.ApproverProfileRepository;
import com.sairo.be.domain.visit.repository.ApproverProfileRepository.LinkRow;
import com.sairo.be.domain.visit.repository.ApproverProfileRepository.PortalRow;
import com.sairo.be.domain.visit.repository.ApproverProfileRepository.ProfileRow;
import com.sairo.be.domain.visit.repository.ConsentGuestNoticeAckRepository;
import com.sairo.be.domain.visit.repository.PropertyAccessApprovalRepository;
import com.sairo.be.domain.visit.repository.VisitApproverConditionRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.SecurityProperties;
import com.sairo.be.global.security.SessionKind;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.HmacSupport;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** 승인자 선택적 계정의 프로필·연결·모아보기와 인증 성공 뒤의 저장 처리다(VISIT-24, RESPOND-09). */
@Profile("!migrate")
@Service
public class ApproverAccountService {

  public record ClaimResult(Long profileId, boolean profileCreated, Long claimedApprovalId) {}

  public record CloseResult(Instant closedAt, int unlinkedApprovalCount) {}

  private static final Set<String> DECISION_KEYS = Set.of("decision");
  private static final List<String> ALL_STATES = List.of("LINKED", "PENDING_CONFIRM");

  private final ApproverProfileRepository profileRepository;
  private final PropertyAccessApprovalRepository approvalRepository;
  private final VisitApproverConditionRepository conditionRepository;
  private final ConsentGuestNoticeAckRepository ackRepository;
  private final PropertyReferenceService propertyReferences;
  private final ApproverLinkNoticeProvider noticeProvider;
  private final ApproverPhoneHasher phoneHasher;
  private final AdvisoryLock advisoryLock;
  private final SecurityProperties securityProperties;
  private final Clock clock;

  public ApproverAccountService(
      ApproverProfileRepository profileRepository,
      PropertyAccessApprovalRepository approvalRepository,
      VisitApproverConditionRepository conditionRepository,
      ConsentGuestNoticeAckRepository ackRepository,
      PropertyReferenceService propertyReferences,
      ApproverLinkNoticeProvider noticeProvider,
      ApproverPhoneHasher phoneHasher,
      AdvisoryLock advisoryLock,
      SecurityProperties securityProperties,
      Clock clock) {
    this.profileRepository = profileRepository;
    this.approvalRepository = approvalRepository;
    this.conditionRepository = conditionRepository;
    this.ackRepository = ackRepository;
    this.propertyReferences = propertyReferences;
    this.noticeProvider = noticeProvider;
    this.phoneHasher = phoneHasher;
    this.advisoryLock = advisoryLock;
    this.securityProperties = securityProperties;
    this.clock = clock;
  }

  public String phoneHash(String normalizedPhone) {
    return phoneHasher.hash(normalizedPhone);
  }

  /** 링크 출입승인에 사무소가 등록한 승인자 연락처 해시와 같고 출입승인이 유효할 때만 계정 만들기를 허용한다. */
  @Transactional(readOnly = true)
  public void requireClaimable(Long approvalId, String phoneHash) {
    PropertyAccessApproval approval =
        approvalRepository
            .findById(approvalId)
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    String stored = approval.getApproverPhoneHash();
    if (stored == null
        || !HmacSupport.constantTimeEquals(stored, phoneHash)
        || approval.isRevoked()
        || !approval.getValidUntil().isAfter(clock.instant())) {
      throw new BusinessException(ErrorCode.APPROVER_CLAIM_NOT_ALLOWED);
    }
  }

  /** 로그인 인증번호를 실제로 보낼 번호인지 본다. 같은 번호의 활성 프로필이나 출입승인이 있어야 한다. */
  @Transactional(readOnly = true)
  public boolean loginTargetExists(String phoneHash) {
    return profileRepository.findActiveByHash(phoneHash).isPresent()
        || profileRepository.hasApprovalWithHash(phoneHash, clock.instant());
  }

  @Transactional(readOnly = true)
  public void requireReauthTarget(Long profileId, String phoneHash) {
    ProfileRow profile = activeProfile(profileId);
    if (!HmacSupport.constantTimeEquals(profile.phoneHash(), phoneHash)) {
      throw new BusinessException(ErrorCode.APPROVER_CLAIM_NOT_ALLOWED);
    }
  }

  @Transactional
  public ClaimResult claim(
      String normalizedPhone, Long approvalId, String noticeVersion, String clientIp) {
    String hash = phoneHash(normalizedPhone);
    requireClaimable(approvalId, hash);
    Instant now = now();
    advisoryLock.lockForTransaction("approver:phone:" + hash);
    Optional<ProfileRow> existing = profileRepository.findActiveByHash(hash);
    boolean created = existing.isEmpty();
    Long profileId =
        existing
            .map(ProfileRow::id)
            .orElseGet(() -> profileRepository.insertProfile(hash, last4(normalizedPhone), now));
    advisoryLock.lockForTransaction(ApproverPortalAccess.profileLockKey(profileId));
    advisoryLock.lockForTransaction(ApprovalAccess.lockKey(approvalId));
    Optional<Long> linkedProfile = profileRepository.linkedProfileOf(approvalId);
    if (linkedProfile.isPresent() && !linkedProfile.get().equals(profileId)) {
      throw new BusinessException(ErrorCode.APPROVER_APPROVAL_ALREADY_LINKED);
    }
    Optional<LinkRow> row = profileRepository.lockLink(profileId, approvalId);
    if (row.isEmpty()) {
      profileRepository.insertLinked(profileId, approvalId, now);
    } else if (!"LINKED".equals(row.get().status())) {
      profileRepository.setStatus(row.get().id(), "LINKED", now);
    }
    for (Long candidate : profileRepository.proposalCandidates(profileId, hash, now)) {
      profileRepository.insertProposal(profileId, candidate, now);
    }
    profileRepository.touchLogin(profileId, now);
    String ip = ipHmac(clientIp);
    profileRepository.recordEvent(profileId, approvalId, null, "CLAIM_COMPLETED", null, ip, now);
    Long officeId = officeOfApproval(approvalId);
    ApproverLinkNoticeProvider.Notice notice = noticeProvider.current();
    ackRepository.save(
        ConsentGuestNoticeAck.portal(
            officeId,
            profileId,
            notice.noticeCode(),
            noticeVersion,
            ConsentGuestNoticeAck.ACTION_PORTAL_CLAIM,
            null,
            now));
    return new ClaimResult(profileId, created, approvalId);
  }

  @Transactional
  public Long login(String phoneHash, String clientIp) {
    ProfileRow profile =
        profileRepository
            .findActiveByHash(phoneHash)
            .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID));
    Instant now = now();
    profileRepository.touchLogin(profile.id(), now);
    profileRepository.recordEvent(
        profile.id(), null, null, "LOGIN_SUCCEEDED", null, ipHmac(clientIp), now);
    return profile.id();
  }

  /** 로그인 인증번호 오입력이다. 프로필이 있을 때만 남기며 요청이 실패해도 기록은 남도록 별도 트랜잭션이다. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordLoginFailure(String phoneHash, String clientIp) {
    profileRepository
        .findActiveByHash(phoneHash)
        .ifPresent(
            profile ->
                profileRepository.recordEvent(
                    profile.id(), null, null, "LOGIN_FAILED", null, ipHmac(clientIp), now()));
  }

  @Transactional(readOnly = true)
  public ApproverSessionResponse sessionResponse(
      Long profileId,
      Instant issuedAt,
      Instant reauthUntil,
      Long claimedApprovalId,
      boolean profileCreated) {
    ProfileRow profile = activeProfile(profileId);
    return new ApproverSessionResponse(
        profile.id(),
        masked(profile.phoneLast4()),
        (int) SessionKind.APPROVER.idleTimeout().toMinutes(),
        TimeRules.toDisplayOffset(issuedAt.plus(SessionKind.APPROVER.absoluteTimeout())),
        TimeRules.toDisplayOffset(reauthUntil),
        claimedApprovalId,
        profileCreated,
        profileRepository.countLinks(profileId, "LINKED"),
        profileRepository.countLinks(profileId, "PENDING_CONFIRM"),
        notice());
  }

  @Transactional(readOnly = true)
  public ApproverProfileResponse profile(Long profileId) {
    ProfileRow profile = activeProfile(profileId);
    return new ApproverProfileResponse(
        profile.id(),
        masked(profile.phoneLast4()),
        TimeRules.toDisplayOffset(profile.createdAt()),
        TimeRules.toDisplayOffset(
            profile.lastLoginAt() == null ? profile.createdAt() : profile.lastLoginAt()),
        profileRepository.countLinks(profileId, "LINKED"),
        profileRepository.countLinks(profileId, "PENDING_CONFIRM"));
  }

  @Transactional
  public CloseResult close(Long profileId, String clientIp) {
    advisoryLock.lockForTransaction(ApproverPortalAccess.profileLockKey(profileId));
    ProfileRow profile =
        profileRepository
            .lock(profileId)
            .filter(ProfileRow::active)
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    Instant now = now();
    int unlinked = profileRepository.unlinkAll(profile.id(), now);
    profileRepository.close(profile.id(), now);
    profileRepository.recordEvent(
        profile.id(), null, null, "PROFILE_CLOSED", null, ipHmac(clientIp), now);
    return new CloseResult(now, unlinked);
  }

  @Transactional(readOnly = true)
  public ApproverPortalApprovalListResponse approvals(Long profileId, String linkState) {
    activeProfile(profileId);
    List<String> states = ALL_STATES;
    if (linkState != null && !linkState.isBlank()) {
      List<String> parsed = new ArrayList<>();
      for (String token : linkState.split(",")) {
        String value = token.strip();
        if (!ALL_STATES.contains(value)) {
          throw CustomerInput.invalid(
              "linkState", FieldErrorCode.NOT_ALLOWED, "LINKED 또는 PENDING_CONFIRM이어야 합니다.");
        }
        parsed.add(value);
      }
      states = parsed;
    }
    Instant now = clock.instant();
    List<ApproverPortalApprovalItem> items = new ArrayList<>();
    for (PortalRow row : profileRepository.portalRows(profileId, states, now)) {
      items.add(item(row, now));
    }
    return new ApproverPortalApprovalListResponse(items, notice());
  }

  @Transactional
  public ApproverLinkDecisionResponse decide(
      Long profileId, Long approvalId, JsonNode body, String clientIp) {
    CustomerInput.requireObject(body, DECISION_KEYS);
    String decision = CustomerInput.requiredText(body, "decision", 10, false);
    if (!"ACCEPT".equals(decision) && !"REJECT".equals(decision)) {
      throw CustomerInput.invalid(
          "decision", FieldErrorCode.NOT_ALLOWED, "ACCEPT 또는 REJECT여야 합니다.");
    }
    activeProfile(profileId);
    advisoryLock.lockForTransaction(ApproverPortalAccess.profileLockKey(profileId));
    LinkRow row =
        profileRepository
            .lockLink(profileId, approvalId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!"PENDING_CONFIRM".equals(row.status())) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant now = now();
    String ip = ipHmac(clientIp);
    if ("ACCEPT".equals(decision)) {
      advisoryLock.lockForTransaction(ApprovalAccess.lockKey(approvalId));
      if (profileRepository.linkedProfileOf(approvalId).isPresent()) {
        throw new BusinessException(ErrorCode.APPROVER_APPROVAL_ALREADY_LINKED);
      }
      profileRepository.setStatus(row.id(), "LINKED", now);
      profileRepository.recordEvent(profileId, approvalId, null, "APPROVAL_LINKED", null, ip, now);
      return new ApproverLinkDecisionResponse(approvalId, "LINKED");
    }
    profileRepository.setStatus(row.id(), "REJECTED", now);
    profileRepository.recordEvent(profileId, approvalId, null, "APPROVAL_REJECTED", null, ip, now);
    return new ApproverLinkDecisionResponse(approvalId, "REJECTED");
  }

  @Transactional
  public void unlink(Long profileId, Long approvalId, String clientIp) {
    activeProfile(profileId);
    advisoryLock.lockForTransaction(ApproverPortalAccess.profileLockKey(profileId));
    LinkRow row =
        profileRepository
            .lockLink(profileId, approvalId)
            .filter(found -> "LINKED".equals(found.status()))
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    Instant now = now();
    profileRepository.unlink(row.id(), now);
    profileRepository.recordEvent(
        profileId, approvalId, null, "APPROVAL_UNLINKED", null, ipHmac(clientIp), now);
  }

  public ProfileRow activeProfile(Long profileId) {
    return profileRepository
        .find(profileId)
        .filter(ProfileRow::active)
        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
  }

  public PrivacyNotice notice() {
    ApproverLinkNoticeProvider.Notice notice = noticeProvider.current();
    return new PrivacyNotice(notice.noticeCode(), notice.noticeVersion(), notice.retentionDays());
  }

  public String ipHmac(String clientIp) {
    return clientIp == null
        ? null
        : HmacSupport.hmacHex(securityProperties.rateLimitHmacSecret(), "approver-ip|" + clientIp);
  }

  private ApproverPortalApprovalItem item(PortalRow row, Instant now) {
    boolean linked = "LINKED".equals(row.linkState());
    boolean primary = "PRIMARY".equals(row.approvalRole());
    boolean active = row.revokedAt() == null && row.validUntil().isAfter(now);
    Optional<Long> dataApproval =
        primary
            ? Optional.of(row.approvalId())
            : approvalRepository.findUsablePrimaries(row.propertyId(), now).stream()
                .findFirst()
                .map(PropertyAccessApproval::getId);
    Optional<VisitApproverCondition> condition =
        linked
            ? dataApproval.flatMap(
                id ->
                    conditionRepository.findFirstByApprovalIdAndStatus(id, ConditionStatus.ACTIVE))
            : Optional.empty();
    List<Instant> upcoming =
        linked
            ? dataApproval
                .map(id -> profileRepository.upcomingVisitStarts(id, now))
                .orElse(List.of())
            : List.of();
    return new ApproverPortalApprovalItem(
        row.approvalId(),
        row.linkState(),
        linked ? row.propertyName() : null,
        linked ? row.propertyAddress() : null,
        region(row.propertyAddress()),
        row.approverType(),
        row.approvalRole(),
        row.ownerOfficeName(),
        linked ? row.officeContactPhone() : null,
        TimeRules.toDisplayOffset(row.validFrom()),
        TimeRules.toDisplayOffset(row.validUntil()),
        active,
        condition.map(VisitApproverCondition::getVersionNo).orElse(null),
        condition.map(c -> TimeRules.toDisplayOffset(c.getAppliesUntil())).orElse(null),
        linked && primary ? profileRepository.pendingRequestCount(row.approvalId()) : 0,
        upcoming.size(),
        upcoming.isEmpty() ? null : TimeRules.toDisplayOffset(upcoming.get(0)));
  }

  private Long officeOfApproval(Long approvalId) {
    Long propertyId =
        approvalRepository
            .findById(approvalId)
            .map(PropertyAccessApproval::getPropertyId)
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    return propertyReferences
        .find(propertyId)
        .map(PropertyReferenceService.PropertySummary::officeId)
        .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
  }

  /** 시·구·동 수준 지역이다. 주소의 앞 세 낱말까지만 쓴다. */
  static String region(String address) {
    if (address == null || address.isBlank()) {
      return "";
    }
    String[] tokens = address.strip().split("\\s+");
    return String.join(" ", java.util.Arrays.copyOf(tokens, Math.min(3, tokens.length)));
  }

  static String last4(String normalizedPhone) {
    String digits = normalizedPhone.replace("-", "");
    return digits.substring(digits.length() - 4);
  }

  static String masked(String last4) {
    return "***-****-" + (last4 == null ? "****" : last4);
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
