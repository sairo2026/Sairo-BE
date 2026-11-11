package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.property.service.PropertyReferenceService;
import com.sairo.be.domain.visit.dto.response.ApproverVisitRequestCompareResponse;
import com.sairo.be.domain.visit.dto.response.ApproverVisitRequestCompareResponse.ApplicantHistory;
import com.sairo.be.domain.visit.dto.response.ApproverVisitRequestCompareResponse.ApplicantTrust;
import com.sairo.be.domain.visit.dto.response.ApproverVisitRequestCompareResponse.Item;
import com.sairo.be.domain.visit.dto.response.ApproverVisitRequestCompareResponse.PrivacyNotice;
import com.sairo.be.domain.visit.dto.response.ApproverVisitRequestCompareResponse.TrustIndicator;
import com.sairo.be.domain.visit.entity.ConditionStatus;
import com.sairo.be.domain.visit.entity.GroupingPreference;
import com.sairo.be.domain.visit.entity.PropertyAccessApproval;
import com.sairo.be.domain.visit.entity.VisitApproverCondition;
import com.sairo.be.domain.visit.repository.ApproverSelectionRepository;
import com.sairo.be.domain.visit.repository.ApproverSelectionRepository.PendingRow;
import com.sairo.be.domain.visit.repository.ConfirmedVisitRepository;
import com.sairo.be.domain.visit.repository.MembershipNameRepository;
import com.sairo.be.domain.visit.repository.PropertyAccessApprovalRepository;
import com.sairo.be.domain.visit.repository.TrustSummaryRepository;
import com.sairo.be.domain.visit.repository.VisitApproverConditionRepository;
import com.sairo.be.domain.visit.repository.VisitApproverPropertyLinkRepository.LinkHead;
import com.sairo.be.domain.visit.service.ApproverConditionEvaluator.Visit;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.OfficeDirectory;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class ApproverRequestCompareService {

  static final List<String> UNCONFIRMED_INDICATORS =
      List.of("ACCESS_DISTANCE", "PROPERTY_TYPE_FIT", "VERIFIED_DEALS");

  private final PublicApproverLinkResolver linkResolver;
  private final ApproverLinkAccessRecorder accessRecorder;
  private final PropertyAccessApprovalRepository approvalRepository;
  private final VisitApproverConditionRepository conditionRepository;
  private final ApproverSelectionRepository selectionRepository;
  private final ConfirmedVisitRepository confirmedVisits;
  private final TrustSummaryRepository trustRepository;
  private final MembershipNameRepository nameRepository;
  private final OfficeDirectory officeDirectory;
  private final PropertyReferenceService propertyReferences;
  private final ApproverLinkNoticeProvider noticeProvider;
  private final ApproverPortalAccess portalAccess;
  private final Clock clock;

  public ApproverRequestCompareService(
      PublicApproverLinkResolver linkResolver,
      ApproverLinkAccessRecorder accessRecorder,
      PropertyAccessApprovalRepository approvalRepository,
      VisitApproverConditionRepository conditionRepository,
      ApproverSelectionRepository selectionRepository,
      ConfirmedVisitRepository confirmedVisits,
      TrustSummaryRepository trustRepository,
      MembershipNameRepository nameRepository,
      OfficeDirectory officeDirectory,
      PropertyReferenceService propertyReferences,
      ApproverLinkNoticeProvider noticeProvider,
      ApproverPortalAccess portalAccess,
      Clock clock) {
    this.linkResolver = linkResolver;
    this.accessRecorder = accessRecorder;
    this.approvalRepository = approvalRepository;
    this.conditionRepository = conditionRepository;
    this.selectionRepository = selectionRepository;
    this.confirmedVisits = confirmedVisits;
    this.trustRepository = trustRepository;
    this.nameRepository = nameRepository;
    this.officeDirectory = officeDirectory;
    this.propertyReferences = propertyReferences;
    this.noticeProvider = noticeProvider;
    this.portalAccess = portalAccess;
    this.clock = clock;
  }

  @Transactional
  public ApproverVisitRequestCompareResponse list(String token, String clientIp) {
    LinkHead head = linkResolver.resolve(token, clientIp);
    accessRecorder.record(head.getId());
    PropertyAccessApproval approval =
        approvalRepository
            .findById(head.getApprovalId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    if (!approval.isPrimary()) {
      throw new BusinessException(ErrorCode.APPROVER_READ_ONLY);
    }
    return listFor(approval);
  }

  /** 승인자 세션으로 신청 비교 목록을 본다. 대표 승인자 출입승인만 볼 수 있다. */
  @Transactional(readOnly = true)
  public ApproverVisitRequestCompareResponse listForProfile(Long profileId, Long approvalId) {
    return listFor(portalAccess.primary(profileId, approvalId));
  }

  private ApproverVisitRequestCompareResponse listFor(PropertyAccessApproval approval) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Optional<VisitApproverCondition> condition =
        conditionRepository.findFirstByApprovalIdAndStatus(
            approval.getId(), ConditionStatus.ACTIVE);
    List<PendingRow> rows = selectionRepository.pendingOfApproval(approval.getId());
    Map<Long, String> brokerNames =
        nameRepository.displayNames(rows.stream().map(PendingRow::applicantMembershipId).toList());
    Map<Long, String> officeNames = new HashMap<>();
    for (PendingRow row : rows) {
      officeNames.computeIfAbsent(row.applicantOfficeId(), this::officeName);
    }
    Set<Long> preferred = selectionRepository.preferredMembers(approval.getId());
    List<Visit> visits = confirmedVisits.findByApproval(approval.getId());
    GroupingPreference preference =
        condition.map(VisitApproverCondition::getGroupingPreference).orElse(null);

    List<PendingRow> ordered = new ArrayList<>(rows);
    ordered.sort(
        Comparator.comparing((PendingRow row) -> !preferred.contains(row.applicantMembershipId()))
            .thenComparing(
                Comparator.comparingLong((PendingRow row) -> gapKey(row, visits, preference)))
            .thenComparing(PendingRow::submittedAt)
            .thenComparing(PendingRow::id));

    List<Item> items = new ArrayList<>();
    for (PendingRow row : ordered) {
      items.add(
          new Item(
              row.id(),
              row.kind(),
              "PENDING",
              row.version(),
              officeNames.getOrDefault(row.applicantOfficeId(), ""),
              brokerNames.getOrDefault(row.applicantMembershipId(), ""),
              row.applicantOfficeId().equals(row.propertyOfficeId())
                  ? "OWNER_OFFICE"
                  : "GRANTED_OFFICE",
              TimeRules.toDisplayOffset(row.startAt()),
              TimeRules.toDisplayOffset(row.endAt()),
              row.durationMinutes(),
              row.partySize(),
              row.note(),
              TimeRules.toDisplayOffset(row.submittedAt()),
              preferred.contains(row.applicantMembershipId()),
              new ApplicantHistory(
                  selectionRepository.previousCompleted(
                      approval.getPropertyId(), row.applicantMembershipId()),
                  selectionRepository.previousCancelled(
                      approval.getPropertyId(), row.applicantMembershipId())),
              trustView(row.applicantMembershipId(), now)));
    }
    List<String> grantedNames =
        selectionRepository.grantedOfficeIds(approval.getId(), now).stream()
            .map(this::officeName)
            .toList();
    ApproverLinkNoticeProvider.Notice notice = noticeProvider.current();
    String address =
        propertyReferences
            .find(approval.getPropertyId())
            .map(PropertyReferenceService.PropertySummary::address)
            .orElse("");
    return new ApproverVisitRequestCompareResponse(
        address == null ? "" : address,
        condition.map(VisitApproverCondition::getVersionNo).orElse(null),
        approval.getApprovalRole().name(),
        grantedNames,
        TrustSummary.POLICY_VERSION,
        TimeRules.toDisplayOffset(now),
        UNCONFIRMED_INDICATORS,
        items,
        new PrivacyNotice(notice.noticeCode(), notice.noticeVersion(), notice.retentionDays()));
  }

  private ApplicantTrust trustView(Long membershipId, Instant now) {
    TrustSummary summary;
    try {
      summary = trustRepository.summarize(membershipId, now);
    } catch (RuntimeException e) {
      return null;
    }
    boolean available = TrustSummary.AVAILABLE.equals(summary.state());
    List<TrustIndicator> indicators = new ArrayList<>();
    for (TrustSummary.Indicator indicator : summary.indicators()) {
      boolean shown = available && indicator.answeredCount() >= TrustSummary.MIN_ITEM_ANSWERS;
      indicators.add(
          new TrustIndicator(
              indicator.indicator(),
              shown ? indicator.answeredCount() : null,
              shown ? indicator.yesCount() : null,
              shown ? indicator.band() : null));
    }
    return new ApplicantTrust(
        summary.state(),
        available ? summary.overallBand() : null,
        summary.completedVisitCount(),
        summary.evaluatedVisitCount(),
        available ? summary.distinctApproverCount() : null,
        indicators);
  }

  private static long gapKey(PendingRow row, List<Visit> visits, GroupingPreference preference) {
    if (preference == null || visits.isEmpty()) {
      return 0L;
    }
    long gap = Long.MAX_VALUE;
    for (Visit visit : visits) {
      long distance;
      if (row.startAt().isBefore(visit.endAt()) && visit.startAt().isBefore(row.endAt())) {
        distance = 0;
      } else if (!row.startAt().isBefore(visit.endAt())) {
        distance = Duration.between(visit.endAt(), row.startAt()).toMinutes();
      } else {
        distance = Duration.between(row.endAt(), visit.startAt()).toMinutes();
      }
      gap = Math.min(gap, distance);
    }
    return preference == GroupingPreference.CONSECUTIVE ? gap : -gap;
  }

  private String officeName(Long officeId) {
    return officeDirectory.find(officeId).map(OfficeDirectory.OfficeSummary::name).orElse("");
  }
}
