package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.customer.service.CustomerReferenceService;
import com.sairo.be.domain.visit.dto.response.ScheduleOutcomeResponses.AffectedScheduleItem;
import com.sairo.be.domain.visit.dto.response.ScheduleOutcomeResponses.CancelledScheduleItem;
import com.sairo.be.domain.visit.dto.response.ScheduleOutcomeResponses.CandidateRef;
import com.sairo.be.domain.visit.dto.response.ScheduleOutcomeResponses.RescheduledScheduleItem;
import com.sairo.be.domain.visit.dto.response.ScheduleOutcomeResponses.VisitAccessRevocationResponse;
import com.sairo.be.domain.visit.dto.response.ScheduleOutcomeResponses.VisitCoordinationCancelResponse;
import com.sairo.be.domain.visit.dto.response.ScheduleOutcomeResponses.VisitCoordinationRescheduleResponse;
import com.sairo.be.domain.visit.entity.CoordinationStatus;
import com.sairo.be.domain.visit.entity.PropertyAccessApproval;
import com.sairo.be.domain.visit.entity.RequestStatusSource;
import com.sairo.be.domain.visit.entity.RescheduleTriggerType;
import com.sairo.be.domain.visit.entity.StatusChangeSource;
import com.sairo.be.domain.visit.entity.VisitCandidateProperty;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.entity.VisitRequest;
import com.sairo.be.domain.visit.mapper.VisitCoordinationMapper;
import com.sairo.be.domain.visit.repository.ConfirmedVisitRepository;
import com.sairo.be.domain.visit.repository.PropertyAccessApprovalRepository;
import com.sairo.be.domain.visit.repository.ScheduleItemRepository;
import com.sairo.be.domain.visit.repository.ScheduleItemRepository.ItemRow;
import com.sairo.be.domain.visit.repository.ScheduleResultRepository;
import com.sairo.be.domain.visit.repository.ScheduleResultRepository.ItemHead;
import com.sairo.be.domain.visit.repository.VisitCandidatePropertyRepository;
import com.sairo.be.domain.visit.repository.VisitCoordinationRepository;
import com.sairo.be.domain.visit.repository.VisitRequestRepository;
import com.sairo.be.domain.visit.service.ScheduleStateService.ItemState;
import com.sairo.be.domain.visit.service.ScheduleStateService.Plan;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** 조율 건 취소, 재조율 필요 전환, 출입승인 철회 접수를 처리한다. */
@Profile("!migrate")
@Service
public class CoordinationDisruptionService {

  private static final int REASON_MAX = 500;
  private static final Set<String> REASON_KEYS = Set.of("reason");
  private static final Set<String> REVOCATION_KEYS =
      Set.of("approvalId", "revokedAt", "revocationReason");
  static final String OCCUPANT_REASON = "점유자 변경 등으로 재조율이 필요하다.";

  private final CoordinationAccess access;
  private final VisitCoordinationRepository coordinationRepository;
  private final VisitCandidatePropertyRepository candidateRepository;
  private final ScheduleItemRepository itemRepository;
  private final ScheduleResultRepository resultRepository;
  private final PropertyAccessApprovalRepository approvalRepository;
  private final VisitRequestRepository requestRepository;
  private final ConfirmedVisitRepository confirmedVisits;
  private final CoordinationStatusService statusService;
  private final ScheduleOutcomeService outcomeService;
  private final ScheduleStateService stateService;
  private final CustomerReferenceService customerReferences;
  private final ApplyGrantService grantService;
  private final Clock clock;

  public CoordinationDisruptionService(
      CoordinationAccess access,
      VisitCoordinationRepository coordinationRepository,
      VisitCandidatePropertyRepository candidateRepository,
      ScheduleItemRepository itemRepository,
      ScheduleResultRepository resultRepository,
      PropertyAccessApprovalRepository approvalRepository,
      VisitRequestRepository requestRepository,
      ConfirmedVisitRepository confirmedVisits,
      CoordinationStatusService statusService,
      ScheduleOutcomeService outcomeService,
      ScheduleStateService stateService,
      CustomerReferenceService customerReferences,
      ApplyGrantService grantService,
      Clock clock) {
    this.access = access;
    this.coordinationRepository = coordinationRepository;
    this.candidateRepository = candidateRepository;
    this.itemRepository = itemRepository;
    this.resultRepository = resultRepository;
    this.approvalRepository = approvalRepository;
    this.requestRepository = requestRepository;
    this.confirmedVisits = confirmedVisits;
    this.statusService = statusService;
    this.outcomeService = outcomeService;
    this.stateService = stateService;
    this.customerReferences = customerReferences;
    this.grantService = grantService;
    this.clock = clock;
  }

  @Transactional
  public VisitCoordinationCancelResponse cancel(
      StaffPrincipal principal, Long coordinationId, long expectedVersion, JsonNode body) {
    String reason = optionalReason(body);
    VisitCoordination coordination = locked(principal, coordinationId, expectedVersion);
    if (coordination.getStatus().isFinished()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    List<ItemRow> before = itemRepository.listByCoordination(coordinationId);
    boolean recorded =
        before.stream()
            .anyMatch(row -> "COMPLETED".equals(row.result()) || "NO_SHOW".equals(row.result()));
    boolean completed = before.stream().anyMatch(row -> "COMPLETED".equals(row.result()));
    for (ItemHead item : resultRepository.scheduledOf(coordinationId)) {
      outcomeService.cancelItem(
          item,
          ScheduleOutcomeService.USER_CANCEL,
          principal.membershipId(),
          RequestStatusSource.BROKER,
          principal.membershipId(),
          now,
          null);
    }
    outcomeService.withdrawActive(coordination, principal.membershipId(), now);
    outcomeService.revokeCustomerLink(coordinationId, now);
    CoordinationStatus next =
        !recorded
            ? CoordinationStatus.CANCELLED
            : completed ? CoordinationStatus.VISIT_COMPLETED : CoordinationStatus.VISIT_INCOMPLETE;
    statusService.move(
        coordination,
        next,
        StatusChangeSource.STAFF,
        principal.membershipId(),
        next == CoordinationStatus.CANCELLED ? reason : "결과 기록 뒤 남은 예정 방문 취소");
    coordinationRepository.saveAndFlush(coordination);
    List<CancelledScheduleItem> items =
        itemRepository.listByCoordination(coordinationId).stream()
            .map(
                row ->
                    new CancelledScheduleItem(
                        row.id(),
                        row.candidatePropertyId(),
                        TimeRules.toDisplayOffset(row.startAt()),
                        TimeRules.toDisplayOffset(row.endAt()),
                        row.result()))
            .toList();
    return new VisitCoordinationCancelResponse(
        coordination.getId(),
        coordination.getStatus().name(),
        coordination.getCustomerId(),
        customerName(coordination),
        coordination.getManagerMembershipId(),
        coordination.getPeriodStartDate(),
        coordination.getPeriodEndDate(),
        candidates(coordinationId),
        items,
        List.of(),
        TimeRules.toDisplayOffset(coordination.getCreatedAt()),
        TimeRules.toDisplayOffset(coordination.getUpdatedAt()),
        coordination.getVersion(),
        coordination.getOfficeId());
  }

  @Transactional
  public VisitCoordinationRescheduleResponse reschedule(
      StaffPrincipal principal, Long coordinationId, long expectedVersion, JsonNode body) {
    String reason = optionalReason(body);
    VisitCoordination coordination = locked(principal, coordinationId, expectedVersion);
    if (coordination.getStatus() != CoordinationStatus.CONFIRMED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant now = clock.instant();
    statusService.raiseReschedule(
        coordination,
        RescheduleTriggerType.OCCUPANT_CHANGED,
        StatusChangeSource.STAFF,
        principal.membershipId(),
        reason == null ? OCCUPANT_REASON : reason);
    outcomeService.revokeCustomerLink(coordinationId, now);
    coordinationRepository.saveAndFlush(coordination);
    List<ItemRow> rows = itemRepository.listByCoordination(coordinationId);
    Map<Long, ItemState> states = stateService.states(coordination, rows);
    Plan plan = stateService.plan(coordination, rows, states);
    notifyWithoutAlternative(plan.noAlternativeItemIds(), rows);
    List<RescheduledScheduleItem> items = new ArrayList<>();
    List<Long> fixed = new ArrayList<>();
    List<AffectedScheduleItem> affected = new ArrayList<>();
    for (ItemRow row : rows) {
      ItemState state = states.get(row.id());
      items.add(
          new RescheduledScheduleItem(
              row.id(),
              row.candidatePropertyId(),
              TimeRules.toDisplayOffset(row.startAt()),
              TimeRules.toDisplayOffset(row.endAt()),
              row.result(),
              state.state()));
      if (state.affected()) {
        affected.add(
            new AffectedScheduleItem(
                row.id(), row.candidatePropertyId(), state.state(), state.reason()));
      } else if (row.kept()) {
        fixed.add(row.id());
      }
    }
    return new VisitCoordinationRescheduleResponse(
        coordination.getId(),
        coordination.getStatus().name(),
        coordination.getCustomerId(),
        customerName(coordination),
        coordination.getManagerMembershipId(),
        coordination.getPeriodStartDate(),
        coordination.getPeriodEndDate(),
        candidates(coordinationId),
        items,
        fixed,
        affected,
        VisitCoordinationMapper.alternatives(plan.alternatives()),
        plan.noAlternativeItemIds(),
        TimeRules.toDisplayOffset(coordination.getCreatedAt()),
        TimeRules.toDisplayOffset(coordination.getUpdatedAt()),
        coordination.getVersion(),
        coordination.getOfficeId());
  }

  @Transactional
  public VisitAccessRevocationResponse revokeAccess(
      StaffPrincipal principal, Long coordinationId, long expectedVersion, JsonNode body) {
    CustomerInput.requireObject(body, REVOCATION_KEYS);
    Long approvalId = CustomerInput.requiredLong(body, "approvalId");
    OffsetDateTime revokedAt = CustomerInput.requiredDateTime(body, "revokedAt");
    String reason = CustomerInput.requiredText(body, "revocationReason", REASON_MAX, false);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    if (revokedAt.toInstant().isAfter(now)) {
      throw CustomerInput.invalid("revokedAt", FieldErrorCode.OUT_OF_RANGE, "미래 시각은 입력할 수 없습니다.");
    }
    VisitCoordination coordination = locked(principal, coordinationId, expectedVersion);
    Set<Long> ownProperties =
        new java.util.HashSet<>(
            candidateRepository
                .findByCoordinationIdOrderBySortOrderAscIdAsc(coordinationId)
                .stream()
                .filter(candidate -> candidate.getPropertyOfficeId().equals(principal.officeId()))
                .map(VisitCandidateProperty::getPropertyId)
                .toList());
    PropertyAccessApproval approval =
        approvalRepository
            .findForUpdateById(approvalId)
            .filter(found -> ownProperties.contains(found.getPropertyId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!approval.isRevoked()) {
      approval.revoke(
          revokedAt.toInstant().truncatedTo(ChronoUnit.MICROS), reason, now, principal.userId());
      approvalRepository.saveAndFlush(approval);
      grantService.expireForRevokedApproval(approvalId);
      boolean uses = usesApproval(coordinationId, approvalId, now);
      if (uses) {
        outcomeService.revokeCustomerLink(coordinationId, now);
        if (coordination.getStatus() == CoordinationStatus.CONFIRMED) {
          statusService.raiseAccessRevoked(
              coordination, approvalId, principal.membershipId(), reason);
        }
        List<ItemRow> rows = itemRepository.listByCoordination(coordinationId);
        Plan plan = stateService.plan(coordination, rows, stateService.states(coordination, rows));
        notifyWithoutAlternative(plan.noAlternativeItemIds(), rows);
      }
      coordination.touch(now);
      coordinationRepository.saveAndFlush(coordination);
    }
    return new VisitAccessRevocationResponse(
        approval.getId(),
        coordination.getId(),
        coordination.getOfficeId(),
        TimeRules.toDisplayOffset(approval.getRevokedAt()),
        TimeRules.toDisplayOffset(approval.getRevocationRecordedAt()),
        approval.getRevokedByUserId(),
        coordination.getStatus().name(),
        coordination.getVersion());
  }

  private boolean usesApproval(Long coordinationId, Long approvalId, Instant now) {
    for (ItemHead item : resultRepository.scheduledOf(coordinationId)) {
      if (item.confirmedRequestId() == null || item.endAt() == null || !item.endAt().isAfter(now)) {
        continue;
      }
      Optional<VisitRequest> request = requestRepository.findById(item.confirmedRequestId());
      if (request.isPresent() && request.get().getApprovalId().equals(approvalId)) {
        return true;
      }
    }
    return false;
  }

  private void notifyWithoutAlternative(List<Long> itemIds, List<ItemRow> rows) {
    for (ItemRow row : rows) {
      if (!itemIds.contains(row.id()) || row.confirmedRequestId() == null) {
        continue;
      }
      requestRepository
          .findById(row.confirmedRequestId())
          .ifPresent(
              request ->
                  confirmedVisits.recordConditionChangeNotice(
                      request.getId(), request.getApplicantMembershipId()));
    }
  }

  private VisitCoordination locked(
      StaffPrincipal principal, Long coordinationId, long expectedVersion) {
    VisitCoordination coordination = access.lock(principal, coordinationId);
    if (coordination.getVersion() != expectedVersion) {
      throw BusinessException.versionConflict(coordination.getVersion());
    }
    return coordination;
  }

  private static String optionalReason(JsonNode body) {
    if (body == null || body.isNull() || body.isMissingNode()) {
      return null;
    }
    CustomerInput.requireObject(body, REASON_KEYS);
    return CustomerInput.optionalText(body, "reason", REASON_MAX, false);
  }

  private String customerName(VisitCoordination coordination) {
    return customerReferences
        .names(List.of(coordination.getCustomerId()))
        .getOrDefault(coordination.getCustomerId(), "");
  }

  private List<CandidateRef> candidates(Long coordinationId) {
    return candidateRepository.findByCoordinationIdOrderBySortOrderAscIdAsc(coordinationId).stream()
        .map(
            candidate ->
                new CandidateRef(
                    candidate.getId(), candidate.getPropertyId(), candidate.getSortOrder()))
        .toList();
  }
}
