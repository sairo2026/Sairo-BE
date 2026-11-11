package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.property.service.PropertyReferenceService;
import com.sairo.be.domain.visit.dto.response.ApproverConditionHistoryResponse;
import com.sairo.be.domain.visit.dto.response.ApproverConditionImpactPreviewResponse;
import com.sairo.be.domain.visit.dto.response.ApproverConditionImpactPreviewResponse.AffectedItem;
import com.sairo.be.domain.visit.dto.response.ApproverConditionResponse;
import com.sairo.be.domain.visit.dto.response.ApproverConditionResponse.DateRuleView;
import com.sairo.be.domain.visit.dto.response.ApproverConditionResponse.LimitView;
import com.sairo.be.domain.visit.dto.response.ApproverConditionResponse.PrivacyNotice;
import com.sairo.be.domain.visit.dto.response.ApproverConditionResponse.WindowView;
import com.sairo.be.domain.visit.entity.ConditionStatus;
import com.sairo.be.domain.visit.entity.ConsentGuestNoticeAck;
import com.sairo.be.domain.visit.entity.InputSource;
import com.sairo.be.domain.visit.entity.PropertyAccessApproval;
import com.sairo.be.domain.visit.entity.RescheduleTriggerType;
import com.sairo.be.domain.visit.entity.StatusChangeSource;
import com.sairo.be.domain.visit.entity.VisitApproverCondition;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.repository.ApproverConditionRowRepository;
import com.sairo.be.domain.visit.repository.ApproverProfileRepository;
import com.sairo.be.domain.visit.repository.ConfirmedVisitRepository;
import com.sairo.be.domain.visit.repository.ConsentGuestNoticeAckRepository;
import com.sairo.be.domain.visit.repository.PropertyAccessApprovalRepository;
import com.sairo.be.domain.visit.repository.VisitApproverConditionRepository;
import com.sairo.be.domain.visit.repository.VisitApproverPropertyLinkRepository;
import com.sairo.be.domain.visit.repository.VisitApproverPropertyLinkRepository.LinkHead;
import com.sairo.be.domain.visit.repository.VisitCoordinationRepository;
import com.sairo.be.domain.visit.service.ApproverConditionEvaluator.Violation;
import com.sairo.be.domain.visit.service.ApproverConditionParser.Mode;
import com.sairo.be.domain.visit.service.ApproverConditionParser.Parsed;
import com.sairo.be.domain.visit.service.ConditionSpec.DateRule;
import com.sairo.be.domain.visit.service.ConditionSpec.Limit;
import com.sairo.be.domain.visit.service.ConditionSpec.Window;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class ApproverConditionService {

  static final String IMPACT_REASON = "승인자 조건 변경으로 확정 방문이 새 조건과 맞지 않음";

  private final ApprovalAccess access;
  private final PropertyAccessApprovalRepository approvalRepository;
  private final VisitApproverConditionRepository conditionRepository;
  private final ApproverConditionRowRepository rowRepository;
  private final VisitApproverPropertyLinkRepository linkRepository;
  private final ConfirmedVisitRepository confirmedVisits;
  private final VisitCoordinationRepository coordinationRepository;
  private final CoordinationStatusService statusService;
  private final ConsentGuestNoticeAckRepository ackRepository;
  private final PropertyReferenceService propertyReferences;
  private final ApproverLinkNoticeProvider noticeProvider;
  private final PublicApproverLinkResolver linkResolver;
  private final ApproverLinkAccessRecorder accessRecorder;
  private final AdvisoryLock advisoryLock;
  private final ApproverPortalAccess portalAccess;
  private final ApproverProfileRepository profileRepository;
  private final Clock clock;

  public ApproverConditionService(
      ApprovalAccess access,
      PropertyAccessApprovalRepository approvalRepository,
      VisitApproverConditionRepository conditionRepository,
      ApproverConditionRowRepository rowRepository,
      VisitApproverPropertyLinkRepository linkRepository,
      ConfirmedVisitRepository confirmedVisits,
      VisitCoordinationRepository coordinationRepository,
      CoordinationStatusService statusService,
      ConsentGuestNoticeAckRepository ackRepository,
      PropertyReferenceService propertyReferences,
      ApproverLinkNoticeProvider noticeProvider,
      PublicApproverLinkResolver linkResolver,
      ApproverLinkAccessRecorder accessRecorder,
      AdvisoryLock advisoryLock,
      ApproverPortalAccess portalAccess,
      ApproverProfileRepository profileRepository,
      Clock clock) {
    this.access = access;
    this.approvalRepository = approvalRepository;
    this.conditionRepository = conditionRepository;
    this.rowRepository = rowRepository;
    this.linkRepository = linkRepository;
    this.confirmedVisits = confirmedVisits;
    this.coordinationRepository = coordinationRepository;
    this.statusService = statusService;
    this.ackRepository = ackRepository;
    this.propertyReferences = propertyReferences;
    this.noticeProvider = noticeProvider;
    this.linkResolver = linkResolver;
    this.accessRecorder = accessRecorder;
    this.advisoryLock = advisoryLock;
    this.portalAccess = portalAccess;
    this.profileRepository = profileRepository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public ApproverConditionHistoryResponse history(
      StaffPrincipal principal, Long propertyId, Long approvalId) {
    access.view(principal, propertyId, approvalId);
    List<ApproverConditionHistoryResponse.Item> items = new ArrayList<>();
    for (VisitApproverCondition condition :
        conditionRepository.findByApprovalIdOrderByVersionNoDesc(approvalId)) {
      List<WindowView> windows =
          rowRepository.windowsOf(condition.getId()).stream()
              .map(ApproverConditionService::windowView)
              .toList();
      List<DateRuleView> rules =
          rowRepository.dateRulesOf(condition.getId()).stream()
              .map(ApproverConditionService::dateRuleView)
              .toList();
      items.add(
          new ApproverConditionHistoryResponse.Item(
              condition.getId(),
              condition.getApprovalId(),
              condition.getVersionNo(),
              condition.getStatus().name(),
              TimeRules.toDisplayOffset(condition.getAppliesFrom()),
              TimeRules.toDisplayOffset(condition.getAppliesUntil()),
              condition.getMaxVisitsPerDay(),
              condition.getMinGapMinutes(),
              condition.getMinLeadMinutes(),
              condition.getSingleVisitMaxMinutes(),
              name(condition.getGroupingPreference()),
              condition.getSource().name(),
              windows,
              TimeRules.toDisplayOffset(condition.getRegisteredAt()),
              condition.getSupersededAt() == null
                  ? null
                  : TimeRules.toDisplayOffset(condition.getSupersededAt()),
              rules,
              0,
              condition.getEnteredByProfileId() != null));
    }
    return new ApproverConditionHistoryResponse(items);
  }

  @Transactional
  public ApproverConditionResponse proxy(
      StaffPrincipal principal, Long propertyId, Long approvalId, JsonNode body) {
    access.requireAgentOrOwner(principal);
    PropertyAccessApproval approval = access.lock(principal, propertyId, approvalId);
    Parsed parsed = ApproverConditionParser.parse(body, Mode.PROXY);
    Instant now = now();
    if (!approval.isPrimary() || !approval.isUsableAt(now)) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Optional<VisitApproverCondition> active =
        conditionRepository.findFirstByApprovalIdAndStatus(approvalId, ConditionStatus.ACTIVE);
    if (active.isPresent() && active.get().getSource() == InputSource.DIRECT) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    return save(
        approval,
        parsed,
        InputSource.PROXY,
        principal.membershipId(),
        null,
        null,
        noticeProvider.current(),
        now,
        principal.membershipId());
  }

  @Transactional
  public ApproverConditionResponse get(String token, String clientIp) {
    LinkHead head = linkResolver.resolve(token, clientIp);
    touch(head.getId());
    PropertyAccessApproval approval =
        approvalRepository
            .findById(head.getApprovalId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    return activeView(approval);
  }

  /** 승인자 세션으로 활성 조건을 읽는다. 공동 승인자는 대표 승인자 출입승인의 조건을 읽는다. */
  @Transactional(readOnly = true)
  public ApproverConditionResponse getForProfile(Long profileId, Long approvalId) {
    return activeView(portalAccess.linked(profileId, approvalId));
  }

  private ApproverConditionResponse activeView(PropertyAccessApproval approval) {
    Long conditionApprovalId = approval.getId();
    if (!approval.isPrimary()) {
      conditionApprovalId =
          approvalRepository.findUsablePrimaries(approval.getPropertyId(), now()).stream()
              .map(PropertyAccessApproval::getId)
              .findFirst()
              .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }
    VisitApproverCondition condition =
        conditionRepository
            .findFirstByApprovalIdAndStatus(conditionApprovalId, ConditionStatus.ACTIVE)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    return response(condition, List.of(), 0, noticeProvider.current());
  }

  @Transactional
  public ApproverConditionResponse put(
      String token, JsonNode body, String idempotencyKey, String clientIp) {
    LinkHead head = linkResolver.resolve(token, clientIp);
    Parsed parsed = ApproverConditionParser.parse(body, Mode.LINK_SAVE);
    PropertyAccessApproval approval = lockForLinkWrite(head);
    ApproverLinkNoticeProvider.Notice notice = noticeProvider.current();
    if (!notice.noticeVersion().equals(parsed.acknowledgedNoticeVersion())) {
      throw new BusinessException(ErrorCode.NOTICE_VERSION_CHANGED);
    }
    Instant now = now();
    if (!approval.isUsableAt(now)) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    touch(head.getId());
    Optional<VisitApproverCondition> active =
        conditionRepository.findFirstByApprovalIdAndStatus(
            approval.getId(), ConditionStatus.ACTIVE);
    if (isRetry(idempotencyKey, active, parsed)) {
      return response(
          active.get(), parsed.normalizedRanges(), parsed.dateRuleReplacedCount(), notice);
    }
    return save(approval, parsed, InputSource.DIRECT, null, head.getId(), null, notice, now, null);
  }

  /** 승인자 세션으로 조건 새 버전을 등록한다. 입력한 프로필을 조건 버전과 안내 확인 기록에 남긴다. */
  @Transactional
  public ApproverConditionResponse putForProfile(
      Long profileId, Long approvalId, JsonNode body, String idempotencyKey) {
    Parsed parsed = ApproverConditionParser.parse(body, Mode.LINK_SAVE);
    PropertyAccessApproval approval = portalAccess.lockForWrite(profileId, approvalId);
    ApproverLinkNoticeProvider.Notice notice = noticeProvider.current();
    if (!notice.noticeVersion().equals(parsed.acknowledgedNoticeVersion())) {
      throw new BusinessException(ErrorCode.NOTICE_VERSION_CHANGED);
    }
    Instant now = now();
    if (!approval.isUsableAt(now)) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Optional<VisitApproverCondition> active =
        conditionRepository.findFirstByApprovalIdAndStatus(
            approval.getId(), ConditionStatus.ACTIVE);
    if (isRetry(idempotencyKey, active, parsed)) {
      return response(
          active.get(), parsed.normalizedRanges(), parsed.dateRuleReplacedCount(), notice);
    }
    return save(approval, parsed, InputSource.DIRECT, null, null, profileId, notice, now, null);
  }

  @Transactional
  public ApproverConditionImpactPreviewResponse previewForProfile(
      Long profileId, Long approvalId, JsonNode body) {
    Parsed parsed = ApproverConditionParser.parse(body, Mode.LINK_PREVIEW);
    PropertyAccessApproval approval = portalAccess.lockForWrite(profileId, approvalId);
    List<AffectedItem> affected = affectedFor(approval.getId(), parsed.spec());
    return new ApproverConditionImpactPreviewResponse(!affected.isEmpty(), affected);
  }

  /** 출입승인 한 건에 새 조건을 적용했을 때 영향받는 확정 방문이다. 저장하지 않는다. */
  @Transactional(readOnly = true)
  public List<AffectedItem> affectedFor(Long approvalId, ConditionSpec spec) {
    return affectedItems(
        ApproverConditionEvaluator.evaluate(
            spec, confirmedVisits.findByApproval(approvalId), now()));
  }

  public record BulkItemOutcome(
      String result,
      Long conditionId,
      Integer versionNo,
      String failureCode,
      int affectedItemCount) {}

  /** 일괄 적용의 출입승인 한 건이다. 출입승인마다 독립 트랜잭션이며 이 출입승인의 잠금 키 하나만 잡는다. 영향 방문이 있는데 확인 목록에 없으면 적용하지 않는다. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public BulkItemOutcome applyBulkItem(
      Long profileId,
      Long approvalId,
      Parsed parsed,
      boolean impactAcknowledged,
      ApproverLinkNoticeProvider.Notice notice) {
    if (!profileRepository.isLinked(profileId, approvalId)) {
      return new BulkItemOutcome("FAILED", null, null, "NOT_LINKED", 0);
    }
    advisoryLock.lockForTransaction(ApprovalAccess.lockKey(approvalId));
    Optional<PropertyAccessApproval> locked = approvalRepository.findForUpdateById(approvalId);
    if (locked.isEmpty() || !profileRepository.isLinked(profileId, approvalId)) {
      return new BulkItemOutcome("FAILED", null, null, "NOT_LINKED", 0);
    }
    PropertyAccessApproval approval = locked.get();
    if (!approval.isPrimary()) {
      return new BulkItemOutcome("FAILED", null, null, "NOT_PRIMARY", 0);
    }
    Instant now = now();
    if (!approval.isUsableAt(now)) {
      return new BulkItemOutcome("FAILED", null, null, "APPROVAL_INACTIVE", 0);
    }
    int affected = affectedFor(approvalId, parsed.spec()).size();
    if (affected > 0 && !impactAcknowledged) {
      return new BulkItemOutcome("NEEDS_IMPACT_ACK", null, null, null, affected);
    }
    Parsed acknowledged =
        new Parsed(
            parsed.spec(),
            parsed.normalizedRanges(),
            parsed.dateRuleReplacedCount(),
            true,
            notice.noticeVersion());
    ApproverConditionResponse saved =
        save(approval, acknowledged, InputSource.DIRECT, null, null, profileId, notice, now, null);
    return new BulkItemOutcome("APPLIED", saved.conditionId(), saved.versionNo(), null, affected);
  }

  @Transactional
  public ApproverConditionImpactPreviewResponse preview(
      String token, JsonNode body, String clientIp) {
    LinkHead head = linkResolver.resolve(token, clientIp);
    Parsed parsed = ApproverConditionParser.parse(body, Mode.LINK_PREVIEW);
    PropertyAccessApproval approval = lockForLinkWrite(head);
    touch(head.getId());
    List<AffectedItem> affected =
        affectedItems(
            ApproverConditionEvaluator.evaluate(
                parsed.spec(), confirmedVisits.findByApproval(approval.getId()), now()));
    return new ApproverConditionImpactPreviewResponse(!affected.isEmpty(), affected);
  }

  private PropertyAccessApproval lockForLinkWrite(LinkHead head) {
    advisoryLock.lockForTransaction(ApprovalAccess.lockKey(head.getApprovalId()));
    PropertyAccessApproval approval =
        approvalRepository
            .findForUpdateById(head.getApprovalId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    if (linkRepository.findById(head.getId()).filter(l -> l.getRevokedAt() == null).isEmpty()) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    }
    if (!approval.isPrimary()) {
      throw new BusinessException(ErrorCode.APPROVER_READ_ONLY);
    }
    return approval;
  }

  private ApproverConditionResponse save(
      PropertyAccessApproval approval,
      Parsed parsed,
      InputSource source,
      Long recordedByMembershipId,
      Long linkId,
      Long profileId,
      ApproverLinkNoticeProvider.Notice notice,
      Instant now,
      Long actorMembershipId) {
    ConditionSpec spec = parsed.spec();
    List<Violation> violations =
        ApproverConditionEvaluator.evaluate(
            spec, confirmedVisits.findByApproval(approval.getId()), now);
    List<AffectedItem> affected = affectedItems(violations);
    if (!affected.isEmpty() && !parsed.impactAcknowledged()) {
      throw BusinessException.impactNotAcknowledged(affected);
    }
    conditionRepository
        .findFirstByApprovalIdAndStatus(approval.getId(), ConditionStatus.ACTIVE)
        .ifPresent(
            previous -> {
              previous.supersede(now);
              conditionRepository.saveAndFlush(previous);
            });
    int versionNo = conditionRepository.maxVersionNo(approval.getId()) + 1;
    VisitApproverCondition registered =
        VisitApproverCondition.register(
            approval.getId(),
            versionNo,
            spec.appliesFrom().truncatedTo(ChronoUnit.MICROS),
            spec.appliesUntil().truncatedTo(ChronoUnit.MICROS),
            spec.maxVisitsPerDay(),
            spec.minGapMinutes(),
            spec.minLeadMinutes(),
            spec.singleVisitMaxMinutes(),
            spec.groupingPreference(),
            source,
            recordedByMembershipId,
            now);
    if (profileId != null) {
      registered.enteredBy(profileId);
    }
    VisitApproverCondition saved = conditionRepository.saveAndFlush(registered);
    rowRepository.saveWindows(saved.getId(), spec.windows());
    rowRepository.saveDateRules(saved.getId(), spec.dateRules());
    if (linkId != null || profileId != null) {
      Long officeId =
          propertyReferences
              .find(approval.getPropertyId())
              .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND))
              .officeId();
      ackRepository.save(
          linkId != null
              ? ConsentGuestNoticeAck.approverConditionSave(
                  officeId, linkId, notice.noticeCode(), notice.noticeVersion(), versionNo, now)
              : ConsentGuestNoticeAck.portal(
                  officeId,
                  profileId,
                  notice.noticeCode(),
                  notice.noticeVersion(),
                  ConsentGuestNoticeAck.ACTION_CONDITION_SAVE,
                  versionNo,
                  now));
    }
    markImpact(violations, source, actorMembershipId);
    return response(saved, parsed.normalizedRanges(), parsed.dateRuleReplacedCount(), notice);
  }

  private void markImpact(List<Violation> violations, InputSource source, Long actorMembershipId) {
    if (violations.isEmpty()) {
      return;
    }
    TreeSet<Long> coordinationIds = new TreeSet<>();
    for (Violation violation : violations) {
      coordinationIds.add(violation.visit().coordinationId());
      confirmedVisits.recordConditionChangeNotice(
          violation.visit().requestId(), violation.visit().applicantMembershipId());
    }
    for (Long coordinationId : coordinationIds) {
      VisitCoordination coordination =
          coordinationRepository.findForUpdateById(coordinationId).orElse(null);
      if (coordination != null
          && coordination.getStatus()
              == com.sairo.be.domain.visit.entity.CoordinationStatus.CONFIRMED) {
        statusService.raiseReschedule(
            coordination,
            RescheduleTriggerType.SCHEDULE_CHANGED,
            source == InputSource.PROXY ? StatusChangeSource.STAFF : StatusChangeSource.APPROVER,
            actorMembershipId,
            IMPACT_REASON);
      }
    }
  }

  private boolean isRetry(
      String idempotencyKey, Optional<VisitApproverCondition> active, Parsed parsed) {
    if (idempotencyKey == null || idempotencyKey.isBlank() || active.isEmpty()) {
      return false;
    }
    VisitApproverCondition current = active.get();
    ConditionSpec spec = parsed.spec();
    if (current.getSource() != InputSource.DIRECT
        || !current.getAppliesFrom().equals(spec.appliesFrom().truncatedTo(ChronoUnit.MICROS))
        || !current.getAppliesUntil().equals(spec.appliesUntil().truncatedTo(ChronoUnit.MICROS))
        || !java.util.Objects.equals(current.getMaxVisitsPerDay(), spec.maxVisitsPerDay())
        || !java.util.Objects.equals(current.getMinGapMinutes(), spec.minGapMinutes())
        || !java.util.Objects.equals(current.getMinLeadMinutes(), spec.minLeadMinutes())
        || !java.util.Objects.equals(
            current.getSingleVisitMaxMinutes(), spec.singleVisitMaxMinutes())
        || current.getGroupingPreference() != spec.groupingPreference()) {
      return false;
    }
    return new HashSet<>(rowRepository.windowsOf(current.getId()))
            .equals(new HashSet<>(spec.windows()))
        && new HashSet<>(rowRepository.dateRulesOf(current.getId()))
            .equals(new HashSet<>(spec.dateRules()));
  }

  private void touch(Long linkId) {
    accessRecorder.record(linkId);
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static List<AffectedItem> affectedItems(List<Violation> violations) {
    return violations.stream()
        .map(
            violation ->
                new AffectedItem(
                    violation.visit().scheduleItemId(),
                    TimeRules.toDisplayOffset(violation.visit().startAt()),
                    TimeRules.toDisplayOffset(violation.visit().endAt()),
                    violation.rules()))
        .toList();
  }

  private ApproverConditionResponse response(
      VisitApproverCondition condition,
      List<Window> normalizedRanges,
      int replacedCount,
      ApproverLinkNoticeProvider.Notice notice) {
    List<WindowView> windows =
        rowRepository.windowsOf(condition.getId()).stream()
            .map(ApproverConditionService::windowView)
            .toList();
    List<DateRuleView> rules =
        rowRepository.dateRulesOf(condition.getId()).stream()
            .map(ApproverConditionService::dateRuleView)
            .toList();
    List<WindowView> normalized =
        normalizedRanges.stream().map(ApproverConditionService::windowView).toList();
    return new ApproverConditionResponse(
        condition.getId(),
        condition.getApprovalId(),
        condition.getVersionNo(),
        condition.getStatus().name(),
        TimeRules.toDisplayOffset(condition.getAppliesFrom()),
        TimeRules.toDisplayOffset(condition.getAppliesUntil()),
        condition.getMaxVisitsPerDay(),
        condition.getMinGapMinutes(),
        condition.getMinLeadMinutes(),
        condition.getSingleVisitMaxMinutes(),
        name(condition.getGroupingPreference()),
        condition.getSource().name(),
        windows,
        normalized,
        !normalized.isEmpty(),
        TimeRules.toDisplayOffset(condition.getRegisteredAt()),
        condition.getSupersededAt() == null
            ? null
            : TimeRules.toDisplayOffset(condition.getSupersededAt()),
        rules,
        replacedCount,
        condition.getEnteredByProfileId() != null,
        notice == null
            ? null
            : new PrivacyNotice(
                notice.noticeCode(), notice.noticeVersion(), notice.retentionDays()));
  }

  private static String name(Enum<?> value) {
    return value == null ? null : value.name();
  }

  static WindowView windowView(Window window) {
    return new WindowView(
        window.type().name(),
        window.weekday(),
        window.date(),
        clock(window.startMinute()),
        clock(window.endMinute()),
        window.exception());
  }

  private static DateRuleView dateRuleView(DateRule rule) {
    return new DateRuleView(
        rule.ruleDate(),
        limitView(rule.maxVisitsPerDay()),
        limitView(rule.minGap()),
        limitView(rule.minLead()),
        limitView(rule.singleVisitMax()));
  }

  private static LimitView limitView(Limit limit) {
    return limit == null ? null : new LimitView(limit.kind().name(), limit.value());
  }

  private static String clock(int minutes) {
    return String.format("%02d:%02d", minutes / 60, minutes % 60);
  }
}
