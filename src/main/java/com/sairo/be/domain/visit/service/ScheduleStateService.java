package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.visit.entity.ConditionStatus;
import com.sairo.be.domain.visit.entity.CoordinationStatus;
import com.sairo.be.domain.visit.entity.PropertyAccessApproval;
import com.sairo.be.domain.visit.entity.RescheduleTriggerType;
import com.sairo.be.domain.visit.entity.VisitApproverCondition;
import com.sairo.be.domain.visit.entity.VisitCandidateProperty;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.entity.VisitRequest;
import com.sairo.be.domain.visit.repository.ConfirmedVisitRepository;
import com.sairo.be.domain.visit.repository.PropertyAccessApprovalRepository;
import com.sairo.be.domain.visit.repository.ScheduleItemRepository.ItemRow;
import com.sairo.be.domain.visit.repository.VisitApproverConditionRepository;
import com.sairo.be.domain.visit.repository.VisitCandidatePropertyRepository;
import com.sairo.be.domain.visit.repository.VisitRequestRepository;
import com.sairo.be.domain.visit.service.ApproverConditionEvaluator.Violation;
import com.sairo.be.domain.visit.service.VisitRequestChecker.Slot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class ScheduleStateService {

  public static final String VALID = "VALID";
  public static final String RESCHEDULE_REQUIRED = "RESCHEDULE_REQUIRED";
  static final int MAX_ALTERNATIVES = 3;

  public record ItemState(Long itemId, Long candidatePropertyId, String state, String reason) {

    public boolean affected() {
      return reason != null;
    }
  }

  public record Alternative(
      Long scheduleItemId, Long candidatePropertyId, Instant startAt, Instant endAt) {}

  public record Plan(List<Alternative> alternatives, List<Long> noAlternativeItemIds) {}

  private final VisitRequestRepository requestRepository;
  private final PropertyAccessApprovalRepository approvalRepository;
  private final VisitApproverConditionRepository conditionRepository;
  private final ConfirmedVisitRepository confirmedVisits;
  private final VisitCandidatePropertyRepository candidateRepository;
  private final FeasibilityContextFactory contextFactory;
  private final ScheduleSlotCalculator slotCalculator;
  private final Clock clock;

  public ScheduleStateService(
      VisitRequestRepository requestRepository,
      PropertyAccessApprovalRepository approvalRepository,
      VisitApproverConditionRepository conditionRepository,
      ConfirmedVisitRepository confirmedVisits,
      VisitCandidatePropertyRepository candidateRepository,
      FeasibilityContextFactory contextFactory,
      ScheduleSlotCalculator slotCalculator,
      Clock clock) {
    this.requestRepository = requestRepository;
    this.approvalRepository = approvalRepository;
    this.conditionRepository = conditionRepository;
    this.confirmedVisits = confirmedVisits;
    this.candidateRepository = candidateRepository;
    this.contextFactory = contextFactory;
    this.slotCalculator = slotCalculator;
    this.clock = clock;
  }

  public Map<Long, ItemState> states(VisitCoordination coordination, List<ItemRow> items) {
    Instant now = clock.instant();
    List<ItemRow> kept =
        items.stream()
            .filter(ItemRow::kept)
            .filter(item -> item.startAt() != null && item.endAt() != null)
            .sorted(Comparator.comparing(ItemRow::startAt).thenComparing(ItemRow::id))
            .toList();
    Map<Long, List<Violation>> violationCache = new HashMap<>();
    Map<Long, ItemState> result = new LinkedHashMap<>();
    ItemRow previous = null;
    for (ItemRow item : kept) {
      String reason = null;
      if ("SCHEDULED".equals(item.result()) && item.endAt().isAfter(now)) {
        reason = reasonOf(coordination, item, previous, now, violationCache);
      }
      result.put(
          item.id(),
          new ItemState(
              item.id(),
              item.candidatePropertyId(),
              reason == null ? VALID : RESCHEDULE_REQUIRED,
              reason));
      previous = item;
    }
    for (ItemRow item : items) {
      result.putIfAbsent(
          item.id(), new ItemState(item.id(), item.candidatePropertyId(), VALID, null));
    }
    return result;
  }

  public boolean hasAffected(Map<Long, ItemState> states) {
    return states.values().stream().anyMatch(ItemState::affected);
  }

  public Plan plan(
      VisitCoordination coordination, List<ItemRow> items, Map<Long, ItemState> states) {
    Instant now = clock.instant();
    List<Alternative> alternatives = new ArrayList<>();
    List<Long> none = new ArrayList<>();
    for (ItemRow item : items) {
      ItemState state = states.get(item.id());
      if (state == null || !state.affected()) {
        continue;
      }
      List<Alternative> found = alternativesFor(coordination, item, now);
      if (found.isEmpty()) {
        none.add(item.id());
      }
      alternatives.addAll(found);
    }
    return new Plan(alternatives, none);
  }

  public List<Slot> changeSlots(VisitCoordination coordination, ItemRow item) {
    Instant now = clock.instant();
    Optional<Usable> usable = usable(item.candidatePropertyId(), now);
    if (usable.isEmpty()) {
      return List.of();
    }
    VisitCandidateProperty candidate = usable.get().candidate();
    return slotCalculator.applicable(
        contextFactory.build(
            coordination,
            candidate,
            usable.get().approval(),
            usable.get().condition(),
            coordination.getManagerMembershipId(),
            item.id()),
        candidate.getVisitDurationMinutes(),
        now,
        now);
  }

  private List<Alternative> alternativesFor(
      VisitCoordination coordination, ItemRow item, Instant now) {
    Optional<Usable> usable = usable(item.candidatePropertyId(), now);
    if (usable.isEmpty()) {
      return List.of();
    }
    int minutes = (int) Duration.between(item.startAt(), item.endAt()).toMinutes();
    Duration length = Duration.ofMinutes(minutes);
    List<Slot> ranges =
        slotCalculator.applicable(
            contextFactory.build(
                coordination,
                usable.get().candidate(),
                usable.get().approval(),
                usable.get().condition(),
                coordination.getManagerMembershipId(),
                item.id()),
            minutes,
            now,
            now);
    List<Alternative> result = new ArrayList<>();
    for (Slot range : ranges) {
      Instant start = range.startsAt();
      while (!start.plus(length).isAfter(range.endsAt()) && result.size() < MAX_ALTERNATIVES) {
        if (!start.equals(item.startAt())) {
          result.add(
              new Alternative(item.id(), item.candidatePropertyId(), start, start.plus(length)));
        }
        start = start.plus(length);
      }
      if (result.size() >= MAX_ALTERNATIVES) {
        break;
      }
    }
    return result;
  }

  private record Usable(
      VisitCandidateProperty candidate,
      PropertyAccessApproval approval,
      VisitApproverCondition condition) {}

  private Optional<Usable> usable(Long candidateId, Instant now) {
    Optional<VisitCandidateProperty> candidate = candidateRepository.findById(candidateId);
    if (candidate.isEmpty()) {
      return Optional.empty();
    }
    Optional<PropertyAccessApproval> approval =
        approvalRepository.findUsablePrimaries(candidate.get().getPropertyId(), now).stream()
            .findFirst();
    if (approval.isEmpty()) {
      return Optional.empty();
    }
    return conditionRepository
        .findFirstByApprovalIdAndStatus(approval.get().getId(), ConditionStatus.ACTIVE)
        .filter(condition -> now.isBefore(condition.getAppliesUntil()))
        .map(condition -> new Usable(candidate.get(), approval.get(), condition));
  }

  private String reasonOf(
      VisitCoordination coordination,
      ItemRow item,
      ItemRow previous,
      Instant now,
      Map<Long, List<Violation>> violationCache) {
    List<String> rules = new ArrayList<>();
    if (previous != null
        && previous.travelEndsAt() != null
        && previous.travelEndsAt().isAfter(item.startAt())) {
      rules.add(VisitFeasibility.TRAVEL_CONFLICT);
    }
    if (coordination.getStatus() == CoordinationStatus.RESCHEDULE_REQUIRED
        && coordination.getRescheduleTriggerType() == RescheduleTriggerType.OCCUPANT_CHANGED
        && item.createdAt() != null
        && !item.createdAt().isAfter(coordination.getRescheduleRaisedAt())) {
      rules.add(VisitRequestChecker.NO_PERMISSION);
    }
    if (item.confirmedRequestId() != null) {
      Optional<VisitRequest> request = requestRepository.findById(item.confirmedRequestId());
      Optional<PropertyAccessApproval> approval =
          request.flatMap(found -> approvalRepository.findById(found.getApprovalId()));
      if (approval.isPresent()) {
        PropertyAccessApproval found = approval.get();
        if (found.isRevoked()
            || found.getAnonymizedAt() != null
            || item.startAt().isBefore(found.getValidFrom())
            || item.endAt().isAfter(found.getValidUntil())) {
          rules.add(VisitRequestChecker.NO_PERMISSION);
        }
        List<Violation> violations =
            violationCache.computeIfAbsent(found.getId(), id -> violationsOf(id, now));
        for (Violation violation : violations) {
          if (violation.visit().scheduleItemId().equals(item.id())) {
            rules.addAll(violation.rules());
          }
        }
      }
    }
    return rules.stream()
        .min(Comparator.comparingInt(VisitFeasibility.RULE_ORDER::indexOf))
        .orElse(null);
  }

  private List<Violation> violationsOf(Long approvalId, Instant now) {
    return conditionRepository
        .findFirstByApprovalIdAndStatus(approvalId, ConditionStatus.ACTIVE)
        .map(
            condition ->
                ApproverConditionEvaluator.evaluate(
                    contextFactory.specOf(condition),
                    confirmedVisits.findByApproval(approvalId),
                    now))
        .orElse(List.of());
  }
}
