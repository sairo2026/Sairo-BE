package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.visit.dto.response.VisitScheduleUpdateResponse;
import com.sairo.be.domain.visit.entity.ConditionStatus;
import com.sairo.be.domain.visit.entity.CoordinationStatus;
import com.sairo.be.domain.visit.entity.PropertyAccessApproval;
import com.sairo.be.domain.visit.entity.StatusChangeSource;
import com.sairo.be.domain.visit.entity.VisitApproverCondition;
import com.sairo.be.domain.visit.entity.VisitCandidateProperty;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.repository.PropertyAccessApprovalRepository;
import com.sairo.be.domain.visit.repository.VisitApproverConditionRepository;
import com.sairo.be.domain.visit.repository.VisitCandidatePropertyRepository;
import com.sairo.be.domain.visit.repository.VisitCoordinationRepository;
import com.sairo.be.domain.visit.service.VisitFeasibility.Context;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class ScheduleComposeService {

  private static final Set<String> KEYS = Set.of("items");
  private static final Set<String> ITEM_KEYS =
      Set.of("candidatePropertyId", "sortOrder", "visitDurationMinutes", "travelDurationMinutes");

  private record Entry(Long candidateId, int sortOrder, int visitMinutes, int travelMinutes) {}

  private final CoordinationAccess access;
  private final VisitCandidatePropertyRepository candidateRepository;
  private final PropertyAccessApprovalRepository approvalRepository;
  private final VisitApproverConditionRepository conditionRepository;
  private final FeasibilityContextFactory contextFactory;
  private final ScheduleSlotCalculator calculator;
  private final CoordinationStatusService statusService;
  private final VisitCoordinationRepository coordinationRepository;
  private final Clock clock;

  public ScheduleComposeService(
      CoordinationAccess access,
      VisitCandidatePropertyRepository candidateRepository,
      PropertyAccessApprovalRepository approvalRepository,
      VisitApproverConditionRepository conditionRepository,
      FeasibilityContextFactory contextFactory,
      ScheduleSlotCalculator calculator,
      CoordinationStatusService statusService,
      VisitCoordinationRepository coordinationRepository,
      Clock clock) {
    this.access = access;
    this.candidateRepository = candidateRepository;
    this.approvalRepository = approvalRepository;
    this.conditionRepository = conditionRepository;
    this.contextFactory = contextFactory;
    this.calculator = calculator;
    this.statusService = statusService;
    this.coordinationRepository = coordinationRepository;
    this.clock = clock;
  }

  @Transactional
  public VisitScheduleUpdateResponse compose(
      StaffPrincipal principal, Long coordinationId, long expectedVersion, JsonNode body) {
    List<Entry> entries = parse(body);
    access.view(principal, coordinationId);
    VisitCoordination coordination = access.lock(principal, coordinationId);
    if (coordination.getVersion() != expectedVersion) {
      throw BusinessException.versionConflict(coordination.getVersion());
    }
    CoordinationStatus status = coordination.getStatus();
    if (status != CoordinationStatus.COORDINATABLE
        && status != CoordinationStatus.FINAL_CONFIRMATION_REQUIRED
        && status != CoordinationStatus.RESCHEDULE_REQUIRED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    List<VisitCandidateProperty> candidates =
        candidateRepository.findByCoordinationIdOrderBySortOrderAscIdAsc(coordinationId);
    Map<Long, VisitCandidateProperty> byId = new HashMap<>();
    candidates.forEach(candidate -> byId.put(candidate.getId(), candidate));
    for (Entry entry : entries) {
      if (!byId.containsKey(entry.candidateId())) {
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
      }
    }
    List<Entry> ordered = new ArrayList<>(entries);
    ordered.sort(Comparator.comparingInt(Entry::sortOrder));
    Set<Long> listed = new HashSet<>();
    int position = 1;
    for (Entry entry : ordered) {
      byId.get(entry.candidateId())
          .arrange(position++, entry.visitMinutes(), entry.travelMinutes());
      listed.add(entry.candidateId());
    }
    for (VisitCandidateProperty candidate : candidates) {
      if (!listed.contains(candidate.getId())) {
        candidate.moveTo(position++);
      }
    }
    candidateRepository.saveAllAndFlush(candidates);

    Instant now = clock.instant();
    Instant cursor =
        ScheduleSlotCalculator.ceil(
            max(now, TimeRules.startOfDisplayDay(coordination.getPeriodStartDate())));
    Instant periodEnd = TimeRules.endOfDisplayDayExclusive(coordination.getPeriodEndDate());
    List<VisitScheduleUpdateResponse.Item> items = new ArrayList<>();
    int index = 1;
    for (Entry entry : ordered) {
      VisitCandidateProperty candidate = byId.get(entry.candidateId());
      PropertyAccessApproval approval =
          approvalRepository.findUsablePrimaries(candidate.getPropertyId(), now).stream()
              .findFirst()
              .orElseThrow(() -> new BusinessException(ErrorCode.TIME_OUT_OF_RANGE));
      VisitApproverCondition condition =
          conditionRepository
              .findFirstByApprovalIdAndStatus(approval.getId(), ConditionStatus.ACTIVE)
              .filter(found -> now.isBefore(found.getAppliesUntil()))
              .orElseThrow(() -> new BusinessException(ErrorCode.TIME_OUT_OF_RANGE));
      Context context =
          contextFactory.build(
              coordination, candidate, approval, condition, principal.membershipId());
      Instant start =
          calculator
              .earliestStart(context, entry.visitMinutes(), cursor, now)
              .orElseThrow(() -> new BusinessException(ErrorCode.TIME_OUT_OF_RANGE));
      Instant end = start.plus(entry.visitMinutes(), ChronoUnit.MINUTES);
      if (end.isAfter(periodEnd)) {
        throw new BusinessException(ErrorCode.TIME_OUT_OF_RANGE);
      }
      items.add(
          new VisitScheduleUpdateResponse.Item(
              candidate.getId(),
              index++,
              TimeRules.toDisplayOffset(start),
              TimeRules.toDisplayOffset(end)));
      cursor = ScheduleSlotCalculator.ceil(end.plus(entry.travelMinutes(), ChronoUnit.MINUTES));
    }
    if (status == CoordinationStatus.COORDINATABLE) {
      statusService.move(
          coordination,
          CoordinationStatus.FINAL_CONFIRMATION_REQUIRED,
          StatusChangeSource.STAFF,
          principal.membershipId(),
          "일정 구성 저장");
    } else {
      coordination.touch(now);
    }
    VisitCoordination saved = coordinationRepository.saveAndFlush(coordination);
    return new VisitScheduleUpdateResponse(
        saved.getId(),
        saved.getStatus().name(),
        items,
        saved.getOfficeId(),
        TimeRules.toDisplayOffset(saved.getCreatedAt()),
        TimeRules.toDisplayOffset(saved.getUpdatedAt()),
        saved.getVersion());
  }

  private static Instant max(Instant first, Instant second) {
    return first.isAfter(second) ? first : second;
  }

  private static List<Entry> parse(JsonNode body) {
    CustomerInput.requireObject(body, KEYS);
    JsonNode items = body.get("items");
    if (items == null || !items.isArray() || items.isEmpty()) {
      throw CustomerInput.invalid("items", FieldErrorCode.REQUIRED, "방문 항목이 1개 이상 필요합니다.");
    }
    List<Entry> entries = new ArrayList<>();
    Set<Long> candidates = new HashSet<>();
    Set<Long> orders = new HashSet<>();
    for (JsonNode item : items) {
      CustomerInput.requireObject(item, ITEM_KEYS);
      Long candidate = CustomerInput.requiredLong(item, "candidatePropertyId");
      Long order = CustomerInput.requiredLong(item, "sortOrder");
      Long visit = CustomerInput.optionalLong(item, "visitDurationMinutes");
      Long travel = CustomerInput.optionalLong(item, "travelDurationMinutes");
      if (order < 1 || order > 1000) {
        throw CustomerInput.invalid("items.sortOrder", FieldErrorCode.OUT_OF_RANGE, "1 이상이어야 합니다.");
      }
      long visitMinutes = visit == null ? VisitCandidateProperty.DEFAULT_VISIT_MINUTES : visit;
      long travelMinutes = travel == null ? VisitCandidateProperty.DEFAULT_TRAVEL_MINUTES : travel;
      if (visitMinutes < 5 || visitMinutes > 240) {
        throw CustomerInput.invalid(
            "items.visitDurationMinutes", FieldErrorCode.OUT_OF_RANGE, "5~240이어야 합니다.");
      }
      if (travelMinutes < 0 || travelMinutes > 240) {
        throw CustomerInput.invalid(
            "items.travelDurationMinutes", FieldErrorCode.OUT_OF_RANGE, "0~240이어야 합니다.");
      }
      if (!candidates.add(candidate)) {
        throw CustomerInput.invalid(
            "items.candidatePropertyId", FieldErrorCode.INVALID_VALUE, "후보 매물이 중복되었습니다.");
      }
      if (!orders.add(order)) {
        throw CustomerInput.invalid(
            "items.sortOrder", FieldErrorCode.INVALID_VALUE, "순서가 중복되었습니다.");
      }
      entries.add(new Entry(candidate, order.intValue(), (int) visitMinutes, (int) travelMinutes));
    }
    return entries;
  }
}
