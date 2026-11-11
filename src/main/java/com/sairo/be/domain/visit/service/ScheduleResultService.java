package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.visit.dto.response.ScheduleOutcomeResponses.AffectedScheduleItem;
import com.sairo.be.domain.visit.dto.response.ScheduleOutcomeResponses.ResultHistoryItem;
import com.sairo.be.domain.visit.dto.response.ScheduleOutcomeResponses.VisitScheduleItemResultHistoryResponse;
import com.sairo.be.domain.visit.dto.response.ScheduleOutcomeResponses.VisitScheduleItemResultResponse;
import com.sairo.be.domain.visit.entity.CoordinationStatus;
import com.sairo.be.domain.visit.entity.RequestStatusSource;
import com.sairo.be.domain.visit.entity.StatusChangeSource;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.repository.MembershipNameRepository;
import com.sairo.be.domain.visit.repository.ScheduleItemRepository;
import com.sairo.be.domain.visit.repository.ScheduleResultRepository;
import com.sairo.be.domain.visit.repository.ScheduleResultRepository.HistoryInsert;
import com.sairo.be.domain.visit.repository.ScheduleResultRepository.HistoryRow;
import com.sairo.be.domain.visit.repository.ScheduleResultRepository.ItemHead;
import com.sairo.be.domain.visit.repository.VisitCoordinationRepository;
import com.sairo.be.domain.visit.service.ScheduleStateService.ItemState;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.port.SettlementRecalculator;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class ScheduleResultService {

  static final Duration REVIEW_WINDOW = Duration.ofDays(14);
  private static final Set<String> KEYS =
      Set.of("result", "actualVisitedAt", "correctionReason", "cancelReasonCode");
  private static final Set<String> RESULTS = Set.of("COMPLETED", "NO_SHOW", "CANCELLED");
  private static final Set<String> CANCEL_CODES =
      Set.of(ScheduleOutcomeService.USER_CANCEL, "APPROVER_REASON", "CUSTOMER_REASON", "OTHER");
  private static final int REASON_MAX = 500;

  private final CoordinationAccess access;
  private final ScheduleResultRepository resultRepository;
  private final ScheduleItemRepository itemRepository;
  private final VisitCoordinationRepository coordinationRepository;
  private final ScheduleOutcomeService outcomeService;
  private final ScheduleStateService stateService;
  private final MembershipNameRepository nameRepository;
  private final SettlementRecalculator settlementRecalculator;
  private final Clock clock;

  public ScheduleResultService(
      CoordinationAccess access,
      ScheduleResultRepository resultRepository,
      ScheduleItemRepository itemRepository,
      VisitCoordinationRepository coordinationRepository,
      ScheduleOutcomeService outcomeService,
      ScheduleStateService stateService,
      MembershipNameRepository nameRepository,
      SettlementRecalculator settlementRecalculator,
      Clock clock) {
    this.access = access;
    this.resultRepository = resultRepository;
    this.itemRepository = itemRepository;
    this.coordinationRepository = coordinationRepository;
    this.outcomeService = outcomeService;
    this.stateService = stateService;
    this.nameRepository = nameRepository;
    this.settlementRecalculator = settlementRecalculator;
    this.clock = clock;
  }

  private record Input(
      String result, Instant actualVisitedAt, String correctionReason, String cancelReasonCode) {}

  @Transactional
  public VisitScheduleItemResultResponse record(
      StaffPrincipal principal, Long itemId, long expectedVersion, JsonNode body) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Input input = parse(body, now);
    ItemHead preview = ownItem(principal, itemId);
    VisitCoordination coordination = access.lock(principal, preview.coordinationId());
    if (coordination.getVersion() != expectedVersion) {
      throw BusinessException.versionConflict(coordination.getVersion());
    }
    ItemHead item =
        resultRepository
            .findItem(itemId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (coordination.getStatus() == CoordinationStatus.CANCELLED
        || "CANCELLED".equals(item.result())) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    boolean correction = !item.scheduled();
    if (correction && "CANCELLED".equals(input.result())) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    if (correction && input.correctionReason() == null) {
      throw CustomerInput.invalid("correctionReason", FieldErrorCode.REQUIRED, "정정할 때는 사유가 필요합니다.");
    }
    List<AffectedScheduleItem> affected = new ArrayList<>();
    if ("CANCELLED".equals(input.result())) {
      outcomeService.cancelItem(
          item,
          input.cancelReasonCode(),
          principal.membershipId(),
          RequestStatusSource.BROKER,
          principal.membershipId(),
          now,
          null);
      affected = affectedAfter(coordination, item.startAt());
    } else {
      resultRepository.insertHistory(
          new HistoryInsert(
              item.id(),
              item.result(),
              input.result(),
              item.actualVisitedAt(),
              input.actualVisitedAt(),
              input.actualVisitedAt() == null ? now : input.actualVisitedAt(),
              now,
              principal.membershipId(),
              correction ? input.correctionReason() : null,
              correction ? resultRepository.latestHistoryId(item.id()).orElse(null) : null,
              null));
      resultRepository.updateItem(
          item.id(),
          input.result(),
          now,
          input.actualVisitedAt(),
          principal.membershipId(),
          correction ? input.correctionReason() : null,
          correction ? item.result() : null);
    }
    coordination.touch(now);
    outcomeService.settle(
        coordination, StatusChangeSource.STAFF, principal.membershipId(), "방문 결과 기록");
    coordinationRepository.saveAndFlush(coordination);
    if (correction) {
      List<Instant> times = new ArrayList<>();
      times.add(item.actualVisitedAt());
      times.add(input.actualVisitedAt());
      settlementRecalculator.recalculateForCoordination(
          coordination.getOfficeId(), coordination.getId(), times);
    }
    OffsetDateTime reviewEndsAt =
        "COMPLETED".equals(input.result()) && item.confirmedRequestId() != null
            ? TimeRules.toDisplayOffset(now.plus(REVIEW_WINDOW))
            : null;
    return new VisitScheduleItemResultResponse(
        item.id(),
        coordination.getId(),
        input.result(),
        TimeRules.toDisplayOffset(input.actualVisitedAt()),
        TimeRules.toDisplayOffset(now),
        reviewEndsAt,
        resultRepository.correctionCount(item.id()),
        coordination.getStatus().name(),
        affected,
        TimeRules.toDisplayOffset(coordination.getUpdatedAt()));
  }

  @Transactional(readOnly = true)
  public VisitScheduleItemResultHistoryResponse history(StaffPrincipal principal, Long itemId) {
    ItemHead item = ownItem(principal, itemId);
    access.view(principal, item.coordinationId());
    List<HistoryRow> rows = resultRepository.history(itemId);
    Map<Long, String> names =
        nameRepository.displayNames(
            rows.stream().map(HistoryRow::recordedByMembershipId).distinct().toList());
    return new VisitScheduleItemResultHistoryResponse(
        rows.stream()
            .map(
                row ->
                    new ResultHistoryItem(
                        row.id(),
                        row.previousResult(),
                        row.result(),
                        TimeRules.toDisplayOffset(row.previousActualVisitedAt()),
                        TimeRules.toDisplayOffset(row.actualVisitedAt()),
                        row.correctionReason(),
                        TimeRules.toDisplayOffset(row.effectiveAt()),
                        TimeRules.toDisplayOffset(row.recordedAt()),
                        names.getOrDefault(row.recordedByMembershipId(), "")))
            .toList());
  }

  ItemHead ownItem(StaffPrincipal principal, Long itemId) {
    return resultRepository
        .findItem(itemId)
        .filter(found -> found.officeId().equals(principal.officeId()))
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  List<AffectedScheduleItem> affectedAfter(VisitCoordination coordination, Instant from) {
    List<AffectedScheduleItem> affected = new ArrayList<>();
    var rows = itemRepository.listByCoordination(coordination.getId());
    Map<Long, ItemState> states = stateService.states(coordination, rows);
    for (var row : rows) {
      ItemState state = states.get(row.id());
      if (state != null
          && state.affected()
          && (from == null || (row.startAt() != null && row.startAt().isAfter(from)))) {
        affected.add(
            new AffectedScheduleItem(
                row.id(), row.candidatePropertyId(), state.state(), state.reason()));
      }
    }
    return affected;
  }

  private Input parse(JsonNode body, Instant now) {
    CustomerInput.requireObject(body, KEYS);
    if (CustomerInput.isNull(body, "result") || !body.get("result").isString()) {
      throw CustomerInput.invalid("result", FieldErrorCode.REQUIRED, "결과는 필수입니다.");
    }
    String result = body.get("result").asString();
    if (!RESULTS.contains(result)) {
      throw CustomerInput.invalid("result", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 결과입니다.");
    }
    OffsetDateTime actual = CustomerInput.optionalDateTime(body, "actualVisitedAt");
    if ("COMPLETED".equals(result)) {
      if (actual == null) {
        throw CustomerInput.invalid(
            "actualVisitedAt", FieldErrorCode.REQUIRED, "방문 완료에는 실제 방문 시각이 필요합니다.");
      }
      if (actual.toInstant().isAfter(now)) {
        throw CustomerInput.invalid(
            "actualVisitedAt", FieldErrorCode.OUT_OF_RANGE, "미래 시각은 입력할 수 없습니다.");
      }
    } else if (actual != null) {
      throw CustomerInput.invalid(
          "actualVisitedAt", FieldErrorCode.NOT_ALLOWED, "방문 완료일 때만 입력합니다.");
    }
    String reason = CustomerInput.optionalText(body, "correctionReason", REASON_MAX, false);
    String code = null;
    if (!CustomerInput.isNull(body, "cancelReasonCode")) {
      if (!"CANCELLED".equals(result) || !body.get("cancelReasonCode").isString()) {
        throw CustomerInput.invalid(
            "cancelReasonCode", FieldErrorCode.NOT_ALLOWED, "취소 결과에만 입력합니다.");
      }
      code = body.get("cancelReasonCode").asString();
      if (!CANCEL_CODES.contains(code)) {
        throw CustomerInput.invalid(
            "cancelReasonCode", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 취소 사유입니다.");
      }
    }
    if ("CANCELLED".equals(result) && code == null) {
      code = ScheduleOutcomeService.USER_CANCEL;
    }
    return new Input(
        result,
        actual == null ? null : actual.toInstant().truncatedTo(ChronoUnit.MICROS),
        reason,
        code);
  }
}
