package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.property.service.ManagingMemberDirectory;
import com.sairo.be.domain.property.service.PropertyReferenceService;
import com.sairo.be.domain.property.service.PropertyReferenceService.PropertySummary;
import com.sairo.be.domain.visit.dto.response.ConfirmedScheduleResponses.ApproverConfirmedScheduleResponse;
import com.sairo.be.domain.visit.dto.response.ConfirmedScheduleResponses.ApproverScheduleItem;
import com.sairo.be.domain.visit.dto.response.ConfirmedScheduleResponses.ConfirmedVisitItem;
import com.sairo.be.domain.visit.dto.response.ConfirmedScheduleResponses.PublicConfirmedVisitResponse;
import com.sairo.be.domain.visit.entity.CoordinationStatus;
import com.sairo.be.domain.visit.entity.PropertyAccessApproval;
import com.sairo.be.domain.visit.entity.VisitCandidateProperty;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.repository.MembershipNameRepository;
import com.sairo.be.domain.visit.repository.PropertyAccessApprovalRepository;
import com.sairo.be.domain.visit.repository.ScheduleItemRepository;
import com.sairo.be.domain.visit.repository.ScheduleItemRepository.ItemRow;
import com.sairo.be.domain.visit.repository.ScheduleResultRepository;
import com.sairo.be.domain.visit.repository.ScheduleResultRepository.ApprovalScheduleRow;
import com.sairo.be.domain.visit.repository.VisitApproverPropertyLinkRepository.LinkHead;
import com.sairo.be.domain.visit.repository.VisitCandidatePropertyRepository;
import com.sairo.be.domain.visit.repository.VisitCoordinationRepository;
import com.sairo.be.domain.visit.repository.VisitCustomerLinkRepository;
import com.sairo.be.domain.visit.service.ScheduleStateService.ItemState;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.OfficeDirectory;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 고객 링크와 승인자 링크의 확정 일정 읽기 전용 조회다. */
@Profile("!migrate")
@Service
public class PublicConfirmedScheduleService {

  static final Duration CUSTOMER_VIEW_AFTER_LAST = Duration.ofHours(24);

  private final PublicLinkResolver customerResolver;
  private final PublicApproverLinkResolver approverResolver;
  private final ApproverLinkAccessRecorder accessRecorder;
  private final VisitCoordinationRepository coordinationRepository;
  private final VisitCandidatePropertyRepository candidateRepository;
  private final ScheduleItemRepository itemRepository;
  private final ScheduleResultRepository resultRepository;
  private final PropertyAccessApprovalRepository approvalRepository;
  private final ScheduleStateService stateService;
  private final PropertyReferenceService propertyReferences;
  private final ManagingMemberDirectory memberDirectory;
  private final MembershipNameRepository nameRepository;
  private final OfficeDirectory officeDirectory;
  private final ApproverPortalAccess portalAccess;
  private final Clock clock;

  public PublicConfirmedScheduleService(
      PublicLinkResolver customerResolver,
      PublicApproverLinkResolver approverResolver,
      ApproverLinkAccessRecorder accessRecorder,
      VisitCoordinationRepository coordinationRepository,
      VisitCandidatePropertyRepository candidateRepository,
      ScheduleItemRepository itemRepository,
      ScheduleResultRepository resultRepository,
      PropertyAccessApprovalRepository approvalRepository,
      ScheduleStateService stateService,
      PropertyReferenceService propertyReferences,
      ManagingMemberDirectory memberDirectory,
      MembershipNameRepository nameRepository,
      OfficeDirectory officeDirectory,
      ApproverPortalAccess portalAccess,
      Clock clock) {
    this.customerResolver = customerResolver;
    this.approverResolver = approverResolver;
    this.accessRecorder = accessRecorder;
    this.coordinationRepository = coordinationRepository;
    this.candidateRepository = candidateRepository;
    this.itemRepository = itemRepository;
    this.resultRepository = resultRepository;
    this.approvalRepository = approvalRepository;
    this.stateService = stateService;
    this.propertyReferences = propertyReferences;
    this.memberDirectory = memberDirectory;
    this.nameRepository = nameRepository;
    this.officeDirectory = officeDirectory;
    this.portalAccess = portalAccess;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PublicConfirmedVisitResponse customer(String token, String clientIp) {
    VisitCustomerLinkRepository.LinkHead head =
        customerResolver.resolveIncludingRevoked(token, clientIp);
    VisitCoordination coordination =
        coordinationRepository
            .findById(head.getCoordinationId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    List<ItemRow> rows = itemRepository.listByCoordination(coordination.getId());
    Instant lastEnd =
        rows.stream()
            .map(ItemRow::endAt)
            .filter(end -> end != null)
            .max(Comparator.naturalOrder())
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    if (!clock.instant().isBefore(lastEnd.plus(CUSTOMER_VIEW_AFTER_LAST))) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_EXPIRED);
    }
    String state = customerState(coordination, head.getRevokedAt() != null, rows);
    List<ConfirmedVisitItem> items = new ArrayList<>();
    if (ScheduleStateService.VALID.equals(state)) {
      List<ItemRow> kept =
          rows.stream()
              .filter(ItemRow::kept)
              .filter(row -> row.startAt() != null && row.endAt() != null)
              .sorted(Comparator.comparing(ItemRow::startAt).thenComparing(ItemRow::id))
              .toList();
      Map<Long, Long> propertyOfCandidate = new HashMap<>();
      for (VisitCandidateProperty candidate :
          candidateRepository.findByCoordinationIdOrderBySortOrderAscIdAsc(coordination.getId())) {
        propertyOfCandidate.put(candidate.getId(), candidate.getPropertyId());
      }
      Map<Long, PropertySummary> properties =
          propertyReferences.findAll(propertyOfCandidate.values());
      int order = 1;
      for (ItemRow row : kept) {
        PropertySummary property =
            properties.get(propertyOfCandidate.get(row.candidatePropertyId()));
        Integer travel =
            row.travelStartsAt() == null || row.travelEndsAt() == null
                ? null
                : (int) Duration.between(row.travelStartsAt(), row.travelEndsAt()).toMinutes();
        items.add(
            new ConfirmedVisitItem(
                order++,
                TimeRules.toDisplayOffset(row.startAt()),
                TimeRules.toDisplayOffset(row.endAt()),
                property == null || property.address() == null ? "" : property.address(),
                travel));
      }
    }
    ManagingMemberDirectory.ManagingMember manager =
        memberDirectory
            .find(coordination.getManagerMembershipId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    String phone =
        officeDirectory
            .find(coordination.getOfficeId())
            .map(OfficeDirectory.OfficeSummary::contactPhone)
            .orElse("");
    return new PublicConfirmedVisitResponse(
        state,
        itemRepository.scheduleVersion(coordination.getId()),
        manager.displayName(),
        PublicCustomerLinkQueryService.legalRole(manager.role()),
        phone == null ? "" : phone,
        items);
  }

  private String customerState(
      VisitCoordination coordination, boolean revoked, List<ItemRow> rows) {
    if (coordination.getStatus() == CoordinationStatus.CANCELLED) {
      return "CANCELLED";
    }
    if (coordination.getStatus() == CoordinationStatus.RESCHEDULE_REQUIRED
        || stateService.hasAffected(stateService.states(coordination, rows))) {
      return ScheduleStateService.RESCHEDULE_REQUIRED;
    }
    return revoked ? "SUPERSEDED" : ScheduleStateService.VALID;
  }

  @Transactional
  public ApproverConfirmedScheduleResponse approver(String token, String clientIp) {
    LinkHead head = approverResolver.resolve(token, clientIp);
    accessRecorder.record(head.getId());
    PropertyAccessApproval approval =
        approvalRepository
            .findById(head.getApprovalId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    return approverFor(approval, ErrorCode.PUBLIC_LINK_NOT_FOUND);
  }

  /** 승인자 세션으로 확정 일정을 읽는다. 링크 경로와 같은 응답이며 공동 승인자도 읽는다. */
  @Transactional(readOnly = true)
  public ApproverConfirmedScheduleResponse approverForProfile(Long profileId, Long approvalId) {
    return approverFor(portalAccess.linked(profileId, approvalId), ErrorCode.RESOURCE_NOT_FOUND);
  }

  private ApproverConfirmedScheduleResponse approverFor(
      PropertyAccessApproval approval, ErrorCode missing) {
    Instant now = clock.instant();
    Optional<Long> primaryId =
        approval.isPrimary()
            ? Optional.of(approval.getId())
            : approvalRepository.findUsablePrimaries(approval.getPropertyId(), now).stream()
                .findFirst()
                .map(PropertyAccessApproval::getId);
    List<ApprovalScheduleRow> rows =
        primaryId.map(resultRepository::approvalSchedule).orElse(List.of());
    Map<Long, Map<Long, ItemState>> statesByCoordination = new HashMap<>();
    for (ApprovalScheduleRow row : rows) {
      statesByCoordination.computeIfAbsent(row.coordinationId(), this::statesOf);
    }
    Map<Long, String> brokers =
        nameRepository.displayNames(
            rows.stream().map(ApprovalScheduleRow::applicantMembershipId).distinct().toList());
    Map<Long, String> offices = new HashMap<>();
    List<ApproverScheduleItem> items = new ArrayList<>();
    for (ApprovalScheduleRow row : rows) {
      ItemState state = statesByCoordination.get(row.coordinationId()).get(row.itemId());
      items.add(
          new ApproverScheduleItem(
              row.itemId(),
              TimeRules.toDisplayOffset(row.startAt()),
              TimeRules.toDisplayOffset(row.endAt()),
              row.result(),
              state == null ? ScheduleStateService.VALID : state.state(),
              row.requestId(),
              offices.computeIfAbsent(row.applicantOfficeId(), this::officeName),
              brokers.getOrDefault(row.applicantMembershipId(), ""),
              row.applicantOfficeId().equals(row.propertyOfficeId())
                  ? "OWNER_OFFICE"
                  : "GRANTED_OFFICE"));
    }
    Long ownerOffice =
        propertyReferences
            .find(approval.getPropertyId())
            .map(PropertySummary::officeId)
            .orElseThrow(() -> new BusinessException(missing));
    Optional<OfficeDirectory.OfficeSummary> office = officeDirectory.find(ownerOffice);
    return new ApproverConfirmedScheduleResponse(
        items,
        office.map(OfficeDirectory.OfficeSummary::name).orElse(""),
        office.map(OfficeDirectory.OfficeSummary::contactPhone).orElse(""));
  }

  private Map<Long, ItemState> statesOf(Long coordinationId) {
    return coordinationRepository
        .findById(coordinationId)
        .map(
            coordination ->
                stateService.states(
                    coordination, itemRepository.listByCoordination(coordinationId)))
        .orElse(Map.of());
  }

  private String officeName(Long officeId) {
    return officeDirectory.find(officeId).map(OfficeDirectory.OfficeSummary::name).orElse("");
  }
}
