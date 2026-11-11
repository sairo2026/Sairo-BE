package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.visit.dto.response.ApproverSelectionResponse;
import com.sairo.be.domain.visit.entity.ConditionStatus;
import com.sairo.be.domain.visit.entity.CoordinationStatus;
import com.sairo.be.domain.visit.entity.PropertyAccessApproval;
import com.sairo.be.domain.visit.entity.RequestKind;
import com.sairo.be.domain.visit.entity.RequestNoticeType;
import com.sairo.be.domain.visit.entity.RequestStatus;
import com.sairo.be.domain.visit.entity.RequestStatusSource;
import com.sairo.be.domain.visit.entity.StatusChangeSource;
import com.sairo.be.domain.visit.entity.VisitApproverCondition;
import com.sairo.be.domain.visit.entity.VisitCandidateProperty;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.entity.VisitRequest;
import com.sairo.be.domain.visit.entity.VisitRequestNotice;
import com.sairo.be.domain.visit.repository.ApproverSelectionRepository;
import com.sairo.be.domain.visit.repository.ApproverSelectionRepository.Outcome;
import com.sairo.be.domain.visit.repository.ApproverSelectionRepository.PendingRow;
import com.sairo.be.domain.visit.repository.PropertyAccessApprovalRepository;
import com.sairo.be.domain.visit.repository.ScheduleBusyRepository;
import com.sairo.be.domain.visit.repository.ScheduleItemRepository;
import com.sairo.be.domain.visit.repository.ScheduleResultRepository;
import com.sairo.be.domain.visit.repository.ScheduleResultRepository.ItemHead;
import com.sairo.be.domain.visit.repository.TrustSummaryRepository;
import com.sairo.be.domain.visit.repository.VisitApproverConditionRepository;
import com.sairo.be.domain.visit.repository.VisitCandidatePropertyRepository;
import com.sairo.be.domain.visit.repository.VisitCoordinationRepository;
import com.sairo.be.domain.visit.repository.VisitRequestNoticeRepository;
import com.sairo.be.domain.visit.repository.VisitRequestRepository;
import com.sairo.be.domain.visit.repository.VisitRequestStatusHistoryRepository;
import com.sairo.be.domain.visit.service.ConditionSpec.RuleItem;
import com.sairo.be.domain.visit.service.VisitFeasibility.Context;
import com.sairo.be.domain.visit.service.VisitRequestChecker.Slot;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.ApplyGrantPort;
import com.sairo.be.global.port.VisitWorkHooks;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.TimeRules;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Component
public class ApproverSelectionExecutor {

  public record Input(
      Long approvalId,
      Long linkId,
      Long profileId,
      Long requestId,
      Long expectedVersion,
      boolean idempotent,
      int comparedCount,
      String note) {}

  public record Result(ApproverSelectionResponse response, ErrorCode rejection, boolean retry) {

    static Result ok(ApproverSelectionResponse response) {
      return new Result(response, null, false);
    }

    static Result rejected(ErrorCode code) {
      return new Result(null, code, false);
    }

    static Result again() {
      return new Result(null, null, true);
    }
  }

  private static final Set<String> CONDITION_RULES =
      Set.of(
          ApproverConditionEvaluator.CONDITION_EXPIRED,
          ApproverConditionEvaluator.WINDOW_MISMATCH,
          ApproverConditionEvaluator.MAX_VISITS_PER_DAY,
          ApproverConditionEvaluator.MIN_GAP,
          ApproverConditionEvaluator.SINGLE_VISIT_MAX);

  private record Failure(String code, String detail) {}

  private final PropertyAccessApprovalRepository approvalRepository;
  private final VisitApproverConditionRepository conditionRepository;
  private final VisitCoordinationRepository coordinationRepository;
  private final VisitCandidatePropertyRepository candidateRepository;
  private final VisitRequestRepository requestRepository;
  private final VisitRequestStatusHistoryRepository historyRepository;
  private final VisitRequestNoticeRepository noticeRepository;
  private final VisitRequestStatusService requestStatusService;
  private final CoordinationStatusService coordinationStatusService;
  private final ApproverSelectionRepository selectionRepository;
  private final ScheduleItemRepository itemRepository;
  private final ScheduleResultRepository resultRepository;
  private final ScheduleOutcomeService outcomeService;
  private final ScheduleBusyRepository busyRepository;
  private final TrustSummaryRepository trustRepository;
  private final FeasibilityContextFactory contextFactory;
  private final ApplyGrantPort applyGrants;
  private final VisitWorkHooks workHooks;
  private final AdvisoryLock advisoryLock;
  private final EntityManager entityManager;
  private final Clock clock;

  public ApproverSelectionExecutor(
      PropertyAccessApprovalRepository approvalRepository,
      VisitApproverConditionRepository conditionRepository,
      VisitCoordinationRepository coordinationRepository,
      VisitCandidatePropertyRepository candidateRepository,
      VisitRequestRepository requestRepository,
      VisitRequestStatusHistoryRepository historyRepository,
      VisitRequestNoticeRepository noticeRepository,
      VisitRequestStatusService requestStatusService,
      CoordinationStatusService coordinationStatusService,
      ApproverSelectionRepository selectionRepository,
      ScheduleItemRepository itemRepository,
      ScheduleResultRepository resultRepository,
      ScheduleOutcomeService outcomeService,
      ScheduleBusyRepository busyRepository,
      TrustSummaryRepository trustRepository,
      FeasibilityContextFactory contextFactory,
      ApplyGrantPort applyGrants,
      VisitWorkHooks workHooks,
      AdvisoryLock advisoryLock,
      EntityManager entityManager,
      Clock clock) {
    this.approvalRepository = approvalRepository;
    this.conditionRepository = conditionRepository;
    this.coordinationRepository = coordinationRepository;
    this.candidateRepository = candidateRepository;
    this.requestRepository = requestRepository;
    this.historyRepository = historyRepository;
    this.noticeRepository = noticeRepository;
    this.requestStatusService = requestStatusService;
    this.coordinationStatusService = coordinationStatusService;
    this.selectionRepository = selectionRepository;
    this.itemRepository = itemRepository;
    this.resultRepository = resultRepository;
    this.outcomeService = outcomeService;
    this.busyRepository = busyRepository;
    this.trustRepository = trustRepository;
    this.contextFactory = contextFactory;
    this.applyGrants = applyGrants;
    this.workHooks = workHooks;
    this.advisoryLock = advisoryLock;
    this.entityManager = entityManager;
    this.clock = clock;
  }

  @Transactional
  public Result execute(Input in) {
    VisitRequest preview =
        requestRepository
            .findById(in.requestId())
            .filter(found -> found.getApprovalId().equals(in.approvalId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    VisitCoordination previewCoordination =
        coordinationRepository
            .findById(preview.getCoordinationId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    VisitCandidateProperty previewCandidate =
        candidateRepository
            .findById(preview.getCandidatePropertyId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    Long officeId = previewCoordination.getOfficeId();
    Long managerId = previewCoordination.getManagerMembershipId();
    Long finalCustomer = busyRepository.rootCustomer(previewCoordination.getCustomerId());
    advisoryLock.lockAllForTransaction(
        List.of(
            ApprovalAccess.lockKey(in.approvalId()),
            ScheduleLockKeys.manager(officeId, managerId),
            ScheduleLockKeys.customer(officeId, finalCustomer),
            ScheduleLockKeys.property(
                previewCandidate.getPropertyOfficeId(), previewCandidate.getPropertyId())));
    entityManager.clear();

    PropertyAccessApproval approval =
        approvalRepository
            .findForUpdateById(in.approvalId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    VisitCoordination coordination =
        coordinationRepository
            .findForUpdateById(previewCoordination.getId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!coordination.getManagerMembershipId().equals(managerId)
        || !busyRepository.rootCustomer(coordination.getCustomerId()).equals(finalCustomer)) {
      return Result.again();
    }
    VisitRequest request =
        requestRepository
            .findForUpdate(in.requestId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (in.idempotent()
        && (request.getStatus() == RequestStatus.CONFIRMED
            || request.getStatus() == RequestStatus.REVALIDATION_FAILED)) {
      Optional<Outcome> prior =
          selectionRepository.priorOutcome(request.getId(), in.linkId(), in.profileId());
      if (prior.isPresent()) {
        return Result.ok(replay(request, prior.get()));
      }
    }
    boolean change = request.getRequestKind() == RequestKind.CHANGE;
    if (request.getStatus() != RequestStatus.PENDING
        || (!change && coordination.getStatus() != CoordinationStatus.APPLYING)
        || (change && coordination.getStatus().isFinished())) {
      throw new BusinessException(ErrorCode.VISIT_REQUEST_INVALID_STATE);
    }
    ItemHead replaced = change ? replacedItem(request) : null;
    if (!in.expectedVersion().equals(request.getVersion())) {
      throw BusinessException.versionConflict(request.getVersion());
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    if (!request.getRequestedStartAt().isAfter(now)) {
      requestStatusService.close(
          request,
          coordination,
          RequestStatus.EXPIRED,
          RequestStatusSource.SYSTEM,
          null,
          "START_PASSED");
      return Result.rejected(ErrorCode.VISIT_REQUEST_INVALID_STATE);
    }

    Long selectionId =
        selectionRepository.insertSelection(
            request.getId(), in.linkId(), in.profileId(), in.comparedCount(), in.note(), now);
    Set<Long> preferred = selectionRepository.preferredMembers(approval.getId());
    for (PendingRow row : selectionRepository.pendingOfApproval(approval.getId())) {
      selectionRepository.insertSnapshot(
          selectionId,
          row.id(),
          row.applicantMembershipId(),
          row.id().equals(request.getId()),
          preferred.contains(row.applicantMembershipId()),
          trustRepository.summarize(row.applicantMembershipId(), now),
          now);
    }
    historyRepository.save(
        request.moveTo(RequestStatus.SELECTED, RequestStatusSource.APPROVER, null, null, now));
    requestRepository.saveAndFlush(request);
    notice(request, RequestNoticeType.SELECTED, now);

    Optional<VisitApproverCondition> active =
        conditionRepository.findFirstByApprovalIdAndStatus(
            approval.getId(), ConditionStatus.ACTIVE);
    VisitCandidateProperty candidate =
        candidateRepository
            .findById(request.getCandidatePropertyId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    Failure failure =
        revalidate(
            request,
            approval,
            coordination,
            candidate,
            active,
            now,
            replaced == null ? null : replaced.id());
    if (failure != null) {
      selectionRepository.insertRevalidation(
          request.getId(),
          selectionId,
          active.map(VisitApproverCondition::getId).orElse(request.getConditionId()),
          "FAILED",
          failure.code(),
          failure.detail(),
          now);
      requestStatusService.close(
          request,
          coordination,
          RequestStatus.REVALIDATION_FAILED,
          RequestStatusSource.APPROVER,
          null,
          failure.code());
      notice(request, RequestNoticeType.REVALIDATION_FAILED, now);
      return Result.ok(
          new ApproverSelectionResponse(
              selectionId,
              request.getId(),
              RequestStatus.REVALIDATION_FAILED.name(),
              new ApproverSelectionResponse.Revalidation(
                  "FAILED", failure.code(), failure.detail(), TimeRules.toDisplayOffset(now)),
              List.of()));
    }
    VisitApproverCondition condition = active.get();
    selectionRepository.insertRevalidation(
        request.getId(), selectionId, condition.getId(), "PASSED", null, null, now);
    historyRepository.save(
        request.moveTo(RequestStatus.CONFIRMED, RequestStatusSource.APPROVER, null, null, now));
    requestRepository.saveAndFlush(request);
    if (replaced != null) {
      outcomeService.cancelItem(
          replaced,
          ScheduleOutcomeService.CHANGE_REPLACED,
          request.getApplicantMembershipId(),
          RequestStatusSource.SYSTEM,
          null,
          now,
          new Slot(request.getRequestedStartAt(), request.getRequestedEndAt()));
    }
    confirmVisit(request, coordination, candidate, now);
    notice(request, RequestNoticeType.CONFIRMED, now);
    List<Long> closed = closeOverlapping(request, condition, selectionId, now);
    long confirmed = itemRepository.confirmedCandidateCount(coordination.getId());
    if (change) {
      outcomeService.settle(coordination, StatusChangeSource.APPROVER, null, "변경 신청 확정");
    } else if (confirmed >= candidateRepository.countByCoordinationId(coordination.getId())) {
      coordinationStatusService.move(
          coordination,
          CoordinationStatus.CONFIRMED,
          StatusChangeSource.APPROVER,
          null,
          "모든 후보 매물이 확정되었다.");
    } else {
      requestStatusService.reopenIfNoActive(
          coordination, RequestStatusSource.APPROVER, null, "확정되지 않은 매물에 새 신청을 받는다.");
    }
    return Result.ok(
        new ApproverSelectionResponse(
            selectionId,
            request.getId(),
            RequestStatus.CONFIRMED.name(),
            new ApproverSelectionResponse.Revalidation(
                "PASSED", null, null, TimeRules.toDisplayOffset(now)),
            closed));
  }

  private ItemHead replacedItem(VisitRequest request) {
    return resultRepository
        .findItem(request.getReplacesScheduleItemId())
        .filter(ItemHead::scheduled)
        .filter(item -> item.coordinationId().equals(request.getCoordinationId()))
        .filter(item -> item.candidatePropertyId().equals(request.getCandidatePropertyId()))
        .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_REQUEST_INVALID_STATE));
  }

  private ApproverSelectionResponse replay(VisitRequest request, Outcome outcome) {
    return new ApproverSelectionResponse(
        outcome.selectionId(),
        request.getId(),
        request.getStatus().name(),
        new ApproverSelectionResponse.Revalidation(
            outcome.result(),
            outcome.failureCode(),
            outcome.failureDetail(),
            TimeRules.toDisplayOffset(outcome.checkedAt())),
        "PASSED".equals(outcome.result())
            ? selectionRepository.timeClosedBySelection(outcome.selectionId())
            : List.of());
  }

  private Failure revalidate(
      VisitRequest request,
      PropertyAccessApproval approval,
      VisitCoordination coordination,
      VisitCandidateProperty candidate,
      Optional<VisitApproverCondition> active,
      Instant now,
      Long excludedItemId) {
    List<String> violated = new ArrayList<>();
    boolean usable = approval.isPrimary() && approval.isUsableAt(now);
    boolean grantLost =
        request.getAccessGrantId() != null
            && applyGrants
                .findActiveGrantId(
                    request.getApplicantOfficeId(),
                    request.getApplicantMembershipId(),
                    approval.getId(),
                    request.getPropertyId())
                .isEmpty();
    if (active.isEmpty()) {
      violated.add(ApproverConditionEvaluator.CONDITION_EXPIRED);
      if (!usable || grantLost) {
        violated.add(VisitRequestChecker.NO_PERMISSION);
      }
    } else {
      Context context =
          contextFactory.build(
              coordination,
              candidate,
              approval,
              active.get(),
              request.getApplicantMembershipId(),
              excludedItemId);
      violated.addAll(
          VisitFeasibility.violated(
              context, request.getRequestedStartAt(), request.getRequestedEndAt(), now, false));
      if ((!usable || grantLost) && !violated.contains(VisitRequestChecker.NO_PERMISSION)) {
        violated.add(VisitRequestChecker.NO_PERMISSION);
      }
    }
    if (violated.isEmpty()) {
      return null;
    }
    violated.sort(Comparator.comparingInt(VisitFeasibility.RULE_ORDER::indexOf));
    boolean conditionChanged =
        active.isPresent() && !active.get().getId().equals(request.getConditionId());
    boolean conditionRuleHit = violated.stream().anyMatch(CONDITION_RULES::contains);
    String code = conditionChanged && conditionRuleHit ? "CONDITION_CHANGED" : violated.get(0);
    String detail = "위반한 규칙: " + String.join(", ", violated);
    return new Failure(code, detail);
  }

  private void confirmVisit(
      VisitRequest request,
      VisitCoordination coordination,
      VisitCandidateProperty candidate,
      Instant now) {
    List<VisitCandidateProperty> all =
        candidateRepository.findByCoordinationIdOrderBySortOrderAscIdAsc(coordination.getId());
    boolean last = all.get(all.size() - 1).getId().equals(candidate.getId());
    Instant travelStart = last ? null : request.getRequestedEndAt();
    Instant travelEnd =
        last
            ? null
            : request
                .getRequestedEndAt()
                .plus(candidate.getTravelDurationMinutes(), ChronoUnit.MINUTES);
    Long itemId =
        itemRepository.insertConfirmed(
            coordination.getId(),
            coordination.getOfficeId(),
            candidate.getId(),
            request.getRequestedStartAt(),
            request.getRequestedEndAt(),
            candidate.getSortOrder(),
            request.getId(),
            travelStart,
            travelEnd);
    workHooks.scheduleItemConfirmed(itemId);
  }

  private List<Long> closeOverlapping(
      VisitRequest confirmed, VisitApproverCondition condition, Long selectionId, Instant now) {
    ConditionSpec spec = contextFactory.specOf(condition);
    List<PendingRow> targets =
        selectionRepository.pendingOfApproval(confirmed.getApprovalId()).stream()
            .filter(row -> !row.id().equals(confirmed.getId()))
            .filter(row -> blocks(spec, confirmed, row))
            .sorted(Comparator.comparing(PendingRow::coordinationId).thenComparing(PendingRow::id))
            .toList();
    TreeSet<Long> closed = new TreeSet<>();
    for (PendingRow row : targets) {
      VisitCoordination coordination =
          coordinationRepository.findForUpdateById(row.coordinationId()).orElse(null);
      VisitRequest request = requestRepository.findForUpdate(row.id()).orElse(null);
      if (coordination == null || request == null || request.getStatus() != RequestStatus.PENDING) {
        continue;
      }
      requestStatusService.close(
          request,
          coordination,
          RequestStatus.TIME_CLOSED,
          RequestStatusSource.APPROVER,
          null,
          "선택 " + selectionId + "번 확정으로 시간 마감");
      notice(request, RequestNoticeType.TIME_CLOSED, now);
      closed.add(request.getId());
    }
    return new ArrayList<>(closed);
  }

  private static boolean blocks(ConditionSpec spec, VisitRequest confirmed, PendingRow other) {
    Instant start = confirmed.getRequestedStartAt();
    Instant end = confirmed.getRequestedEndAt();
    if (TimeRules.overlaps(start, end, other.startAt(), other.endAt())) {
      return true;
    }
    LocalDate confirmedDate = TimeRules.displayDate(start);
    LocalDate otherDate = TimeRules.displayDate(other.startAt());
    int required =
        zeroIfNull(ApproverConditionEvaluator.effective(spec, otherDate, RuleItem.MIN_GAP));
    if (!confirmedDate.equals(otherDate)) {
      required =
          Math.max(
              required,
              zeroIfNull(
                  ApproverConditionEvaluator.effective(spec, confirmedDate, RuleItem.MIN_GAP)));
    }
    if (required == 0) {
      return false;
    }
    long gap =
        other.startAt().isBefore(start)
            ? Duration.between(other.endAt(), start).toMinutes()
            : Duration.between(end, other.startAt()).toMinutes();
    return gap < required;
  }

  private static int zeroIfNull(Integer value) {
    return value == null ? 0 : value;
  }

  private void notice(VisitRequest request, RequestNoticeType type, Instant now) {
    noticeRepository.save(
        VisitRequestNotice.of(request.getId(), request.getApplicantMembershipId(), type, now));
  }
}
