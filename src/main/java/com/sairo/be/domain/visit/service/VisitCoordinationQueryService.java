package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.service.CustomerReferenceService;
import com.sairo.be.domain.property.service.ManagingMemberDirectory;
import com.sairo.be.domain.property.service.PropertyReferenceService;
import com.sairo.be.domain.property.service.PropertyReferenceService.PropertySummary;
import com.sairo.be.domain.visit.dto.response.VisitCoordinationDetailResponse;
import com.sairo.be.domain.visit.dto.response.VisitCoordinationListResponse;
import com.sairo.be.domain.visit.entity.ConditionStatus;
import com.sairo.be.domain.visit.entity.CoordinationStatus;
import com.sairo.be.domain.visit.entity.PropertyAccessApproval;
import com.sairo.be.domain.visit.entity.VisitApproverCondition;
import com.sairo.be.domain.visit.entity.VisitCandidateProperty;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.entity.VisitCustomerLink;
import com.sairo.be.domain.visit.entity.VisitCustomerTimeSlot;
import com.sairo.be.domain.visit.mapper.VisitCoordinationMapper;
import com.sairo.be.domain.visit.repository.PropertyAccessApprovalRepository;
import com.sairo.be.domain.visit.repository.ScheduleItemRepository;
import com.sairo.be.domain.visit.repository.ScheduleItemRepository.ItemRow;
import com.sairo.be.domain.visit.repository.VisitApproverConditionRepository;
import com.sairo.be.domain.visit.repository.VisitCandidatePropertyRepository;
import com.sairo.be.domain.visit.repository.VisitCoordinationReadRepository;
import com.sairo.be.domain.visit.repository.VisitCustomerLinkRepository;
import com.sairo.be.domain.visit.repository.VisitCustomerTimeSlotRepository;
import com.sairo.be.domain.visit.service.ScheduleStateService.ItemState;
import com.sairo.be.domain.visit.service.VisitRequestChecker.Slot;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.port.OfficeDirectory;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.PageQuery;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
@Transactional(readOnly = true)
public class VisitCoordinationQueryService {

  private final CoordinationAccess access;
  private final VisitCoordinationReadRepository readRepository;
  private final VisitCandidatePropertyRepository candidateRepository;
  private final VisitCustomerLinkRepository linkRepository;
  private final VisitCustomerTimeSlotRepository slotRepository;
  private final PropertyReferenceService propertyReferences;
  private final CustomerReferenceService customerReferences;
  private final ManagingMemberDirectory memberDirectory;
  private final OfficeDirectory officeDirectory;
  private final ScheduleItemRepository itemRepository;
  private final PropertyAccessApprovalRepository approvalRepository;
  private final VisitApproverConditionRepository conditionRepository;
  private final FeasibilityContextFactory contextFactory;
  private final ScheduleSlotCalculator slotCalculator;
  private final ScheduleStateService stateService;
  private final Clock clock;

  public VisitCoordinationQueryService(
      CoordinationAccess access,
      VisitCoordinationReadRepository readRepository,
      VisitCandidatePropertyRepository candidateRepository,
      VisitCustomerLinkRepository linkRepository,
      VisitCustomerTimeSlotRepository slotRepository,
      PropertyReferenceService propertyReferences,
      CustomerReferenceService customerReferences,
      ManagingMemberDirectory memberDirectory,
      OfficeDirectory officeDirectory,
      ScheduleItemRepository itemRepository,
      PropertyAccessApprovalRepository approvalRepository,
      VisitApproverConditionRepository conditionRepository,
      FeasibilityContextFactory contextFactory,
      ScheduleSlotCalculator slotCalculator,
      ScheduleStateService stateService,
      Clock clock) {
    this.access = access;
    this.readRepository = readRepository;
    this.candidateRepository = candidateRepository;
    this.linkRepository = linkRepository;
    this.slotRepository = slotRepository;
    this.propertyReferences = propertyReferences;
    this.customerReferences = customerReferences;
    this.memberDirectory = memberDirectory;
    this.officeDirectory = officeDirectory;
    this.itemRepository = itemRepository;
    this.approvalRepository = approvalRepository;
    this.conditionRepository = conditionRepository;
    this.contextFactory = contextFactory;
    this.slotCalculator = slotCalculator;
    this.stateService = stateService;
    this.clock = clock;
  }

  public VisitCoordinationListResponse list(
      StaffPrincipal principal, List<String> statusValues, PageQuery pageQuery) {
    List<CoordinationStatus> statuses = parseStatuses(statusValues);
    Long scope = access.managerScope(principal);
    long officeId = principal.officeId();
    List<VisitCoordinationReadRepository.ListRow> rows =
        readRepository.findPage(
            officeId,
            scope,
            statuses,
            pageQuery.size(),
            (long) pageQuery.page() * pageQuery.size());
    long total = readRepository.count(officeId, scope, statuses);
    Map<CoordinationStatus, Long> counts = readRepository.statusCounts(officeId, scope);

    List<Long> coordinationIds =
        rows.stream().map(VisitCoordinationReadRepository.ListRow::id).toList();
    Map<Long, List<VisitCandidateProperty>> candidatesByCoordination = new LinkedHashMap<>();
    Set<Long> propertyIds = new LinkedHashSet<>();
    if (!coordinationIds.isEmpty()) {
      for (VisitCandidateProperty candidate :
          candidateRepository.findByCoordinationIdInOrderBySortOrderAscIdAsc(coordinationIds)) {
        candidatesByCoordination
            .computeIfAbsent(candidate.getCoordinationId(), key -> new ArrayList<>())
            .add(candidate);
        propertyIds.add(candidate.getPropertyId());
      }
    }
    Map<Long, PropertySummary> properties = propertyReferences.findAll(propertyIds);
    Map<Long, String> customerNames =
        customerReferences.names(
            rows.stream().map(VisitCoordinationReadRepository.ListRow::customerId).toList());
    Map<Long, String> managerNames = managerNames(rows);
    return VisitCoordinationMapper.toList(
        rows,
        counts,
        candidatesByCoordination,
        properties,
        customerNames,
        managerNames,
        officeId,
        pageQuery,
        total);
  }

  public VisitCoordinationDetailResponse detail(
      StaffPrincipal principal, Long coordinationId, Long changeScheduleItemId) {
    VisitCoordination coordination = access.view(principal, coordinationId);
    List<VisitCandidateProperty> candidates =
        candidateRepository.findByCoordinationIdOrderBySortOrderAscIdAsc(coordinationId);
    Map<Long, PropertySummary> properties =
        propertyReferences.findAll(
            candidates.stream().map(VisitCandidateProperty::getPropertyId).toList());
    Map<Long, String> officeNames = new LinkedHashMap<>();
    for (VisitCandidateProperty candidate : candidates) {
      officeNames.computeIfAbsent(
          candidate.getPropertyOfficeId(),
          key -> officeDirectory.find(key).map(OfficeDirectory.OfficeSummary::name).orElse(""));
    }
    Optional<VisitCustomerLink> link =
        linkRepository.findFirstByCoordinationIdOrderByIdDesc(coordinationId);
    List<VisitCustomerTimeSlot> slots =
        link.filter(found -> found.getResponseVersion() > 0)
            .map(
                found ->
                    slotRepository.findByCustomerLinkIdAndResponseVersionOrderByStartsAtAscIdAsc(
                        found.getId(), found.getResponseVersion()))
            .orElse(List.of());
    List<ItemRow> items = itemRepository.listByCoordination(coordinationId);
    Map<Long, List<Slot>> applicable;
    if (changeScheduleItemId != null) {
      ItemRow target =
          items.stream()
              .filter(item -> item.id().equals(changeScheduleItemId))
              .findFirst()
              .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
      applicable = new LinkedHashMap<>();
      if ("SCHEDULED".equals(target.result())) {
        applicable.put(
            target.candidatePropertyId(), stateService.changeSlots(coordination, target));
      }
    } else {
      applicable = applicableSlots(coordination, candidates, items);
    }
    Map<Long, ItemState> states = stateService.states(coordination, items);
    List<ScheduleStateService.Alternative> alternatives =
        coordination.getStatus() == CoordinationStatus.RESCHEDULE_REQUIRED
                || stateService.hasAffected(states)
            ? stateService.plan(coordination, items, states).alternatives()
            : List.of();
    String customerName =
        customerReferences
            .names(List.of(coordination.getCustomerId()))
            .getOrDefault(coordination.getCustomerId(), "");
    String managerName =
        memberDirectory
            .find(coordination.getManagerMembershipId())
            .map(ManagingMemberDirectory.ManagingMember::displayName)
            .orElse("");
    return VisitCoordinationMapper.toDetail(
        coordination,
        candidates,
        properties,
        officeNames,
        link.orElse(null),
        slots,
        customerName,
        managerName,
        items,
        itemRepository.scheduleVersion(coordinationId),
        applicable,
        states,
        alternatives);
  }

  private Map<Long, List<Slot>> applicableSlots(
      VisitCoordination coordination,
      List<VisitCandidateProperty> candidates,
      List<ItemRow> items) {
    Map<Long, List<Slot>> result = new LinkedHashMap<>();
    CoordinationStatus status = coordination.getStatus();
    if (status != CoordinationStatus.FINAL_CONFIRMATION_REQUIRED
        && status != CoordinationStatus.APPLYING) {
      return result;
    }
    Instant now = clock.instant();
    Set<Long> confirmed = new LinkedHashSet<>();
    for (ItemRow item : items) {
      if (item.kept()) {
        confirmed.add(item.candidatePropertyId());
      }
    }
    for (VisitCandidateProperty candidate : candidates) {
      if (confirmed.contains(candidate.getId())) {
        continue;
      }
      Optional<PropertyAccessApproval> approval =
          approvalRepository.findUsablePrimaries(candidate.getPropertyId(), now).stream()
              .findFirst();
      Optional<VisitApproverCondition> condition =
          approval.flatMap(
              found ->
                  conditionRepository.findFirstByApprovalIdAndStatus(
                      found.getId(), ConditionStatus.ACTIVE));
      if (condition.isEmpty() || !now.isBefore(condition.get().getAppliesUntil())) {
        continue;
      }
      List<Slot> slots =
          slotCalculator.applicable(
              contextFactory.build(
                  coordination,
                  candidate,
                  approval.get(),
                  condition.get(),
                  coordination.getManagerMembershipId()),
              candidate.getVisitDurationMinutes(),
              now,
              now);
      if (!slots.isEmpty()) {
        result.put(candidate.getId(), slots);
      }
    }
    return result;
  }

  private Map<Long, String> managerNames(List<VisitCoordinationReadRepository.ListRow> rows) {
    Map<Long, String> names = new LinkedHashMap<>();
    for (VisitCoordinationReadRepository.ListRow row : rows) {
      names.computeIfAbsent(
          row.managerMembershipId(),
          key ->
              memberDirectory
                  .find(key)
                  .map(ManagingMemberDirectory.ManagingMember::displayName)
                  .orElse(""));
    }
    return names;
  }

  private static List<CoordinationStatus> parseStatuses(List<String> values) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    List<CoordinationStatus> statuses = new ArrayList<>();
    for (String value : values) {
      try {
        statuses.add(CoordinationStatus.valueOf(value));
      } catch (IllegalArgumentException e) {
        throw BusinessException.withFieldErrors(
            ErrorCode.INVALID_REQUEST,
            List.of(new FieldErrorItem("status", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 상태 값입니다.")));
      }
    }
    return statuses;
  }
}
