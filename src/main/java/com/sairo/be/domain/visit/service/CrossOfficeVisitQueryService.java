package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.visit.dto.response.ApplyGrantResponses.CrossOfficeRequestItem;
import com.sairo.be.domain.visit.dto.response.ApplyGrantResponses.CrossOfficeScheduleItem;
import com.sairo.be.domain.visit.entity.RequestStatus;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.repository.CrossOfficeVisitRepository;
import com.sairo.be.domain.visit.repository.CrossOfficeVisitRepository.RequestRow;
import com.sairo.be.domain.visit.repository.CrossOfficeVisitRepository.ScheduleRow;
import com.sairo.be.domain.visit.repository.MembershipNameRepository;
import com.sairo.be.domain.visit.repository.ScheduleItemRepository;
import com.sairo.be.domain.visit.repository.ScheduleItemRepository.ItemRow;
import com.sairo.be.domain.visit.repository.VisitCoordinationRepository;
import com.sairo.be.domain.visit.service.ScheduleStateService.ItemState;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.port.OfficeDirectory;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import com.sairo.be.global.support.StatusFilter;
import com.sairo.be.global.support.TimeRules;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 매물을 가진 사무소의 다른 사무소 신청·확정 방문 읽기 전용 현황이다(VISIT-22). */
@Profile("!migrate")
@Service
public class CrossOfficeVisitQueryService {

  private static final Set<String> CLOSE_REASONS =
      Set.of("START_PASSED", "CONDITION_EXPIRED", "GRANT_REVOKED", "GRANT_EXPIRED");
  private static final Set<String> RESULTS =
      Set.of("SCHEDULED", "COMPLETED", "NO_SHOW", "CANCELLED");

  private final ApprovalAccess access;
  private final CrossOfficeVisitRepository repository;
  private final OfficeDirectory officeDirectory;
  private final MembershipNameRepository names;
  private final VisitCoordinationRepository coordinationRepository;
  private final ScheduleItemRepository itemRepository;
  private final ScheduleStateService stateService;

  public CrossOfficeVisitQueryService(
      ApprovalAccess access,
      CrossOfficeVisitRepository repository,
      OfficeDirectory officeDirectory,
      MembershipNameRepository names,
      VisitCoordinationRepository coordinationRepository,
      ScheduleItemRepository itemRepository,
      ScheduleStateService stateService) {
    this.access = access;
    this.repository = repository;
    this.officeDirectory = officeDirectory;
    this.names = names;
    this.coordinationRepository = coordinationRepository;
    this.itemRepository = itemRepository;
    this.stateService = stateService;
  }

  @Transactional(readOnly = true)
  public PageResponse<CrossOfficeRequestItem> requests(
      StaffPrincipal principal, Long propertyId, String statusFilter, PageQuery pageQuery) {
    access.requireAgentOrOwner(principal);
    access.requireOwnProperty(principal, propertyId);
    Set<String> statuses = new LinkedHashSet<>();
    for (RequestStatus status : StatusFilter.parse(statusFilter, RequestStatus.class, Set.of())) {
      statuses.add(status.name());
    }
    long total = repository.countRequests(propertyId, principal.officeId(), statuses);
    List<RequestRow> rows =
        repository.requests(
            propertyId,
            principal.officeId(),
            statuses,
            pageQuery.size(),
            (long) pageQuery.page() * pageQuery.size());
    Set<Long> memberIds = new HashSet<>();
    rows.forEach(row -> memberIds.add(row.applicantMembershipId()));
    Map<Long, String> memberNames = names.displayNames(memberIds);
    Map<Long, String> officeNames = new HashMap<>();
    List<CrossOfficeRequestItem> items = new ArrayList<>();
    for (RequestRow row : rows) {
      String reason =
          "EXPIRED".equals(row.status()) && CLOSE_REASONS.contains(row.closeReason())
              ? row.closeReason()
              : null;
      items.add(
          new CrossOfficeRequestItem(
              row.id(),
              row.requestKind(),
              row.status(),
              officeName(officeNames, row.applicantOfficeId()),
              memberNames.getOrDefault(row.applicantMembershipId(), ""),
              TimeRules.toDisplayOffset(row.requestedStartAt()),
              TimeRules.toDisplayOffset(row.requestedEndAt()),
              row.expectedDurationMinutes(),
              TimeRules.toDisplayOffset(row.submittedAt()),
              TimeRules.toDisplayOffset(row.closedAt()),
              reason));
    }
    return PageResponse.of(items, pageQuery.page(), pageQuery.size(), total);
  }

  @Transactional(readOnly = true)
  public PageResponse<CrossOfficeScheduleItem> schedules(
      StaffPrincipal principal,
      Long propertyId,
      LocalDate from,
      LocalDate to,
      String result,
      PageQuery pageQuery) {
    access.requireAgentOrOwner(principal);
    access.requireOwnProperty(principal, propertyId);
    String resultFilter = result == null || result.isBlank() ? null : result.strip();
    if (resultFilter != null && !RESULTS.contains(resultFilter)) {
      throw BusinessException.withFieldErrors(
          ErrorCode.INVALID_REQUEST,
          List.of(
              new FieldErrorItem("result", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 result 값입니다.")));
    }
    if (from != null && to != null && to.isBefore(from)) {
      throw BusinessException.withFieldErrors(
          ErrorCode.INVALID_REQUEST,
          List.of(new FieldErrorItem("to", FieldErrorCode.OUT_OF_RANGE, "from 이후여야 합니다.")));
    }
    Instant start = from == null ? null : TimeRules.startOfDisplayDay(from);
    Instant end = to == null ? null : TimeRules.endOfDisplayDayExclusive(to);
    long total =
        repository.countSchedules(propertyId, principal.officeId(), start, end, resultFilter);
    List<ScheduleRow> rows =
        repository.schedules(
            propertyId,
            principal.officeId(),
            start,
            end,
            resultFilter,
            pageQuery.size(),
            (long) pageQuery.page() * pageQuery.size());
    Set<Long> memberIds = new HashSet<>();
    rows.forEach(row -> memberIds.add(row.applicantMembershipId()));
    Map<Long, String> memberNames = names.displayNames(memberIds);
    Map<Long, String> officeNames = new HashMap<>();
    Map<Long, Map<Long, ItemState>> statesByCoordination = new HashMap<>();
    List<CrossOfficeScheduleItem> items = new ArrayList<>();
    for (ScheduleRow row : rows) {
      ItemState state =
          statesByCoordination
              .computeIfAbsent(row.coordinationId(), this::statesOf)
              .get(row.itemId());
      items.add(
          new CrossOfficeScheduleItem(
              row.itemId(),
              TimeRules.toDisplayOffset(row.startAt()),
              TimeRules.toDisplayOffset(row.endAt()),
              row.result(),
              state == null ? ScheduleStateService.VALID : state.state(),
              officeName(officeNames, row.applicantOfficeId()),
              memberNames.getOrDefault(row.applicantMembershipId(), ""),
              row.confirmedRequestId()));
    }
    return PageResponse.of(items, pageQuery.page(), pageQuery.size(), total);
  }

  private Map<Long, ItemState> statesOf(Long coordinationId) {
    VisitCoordination coordination = coordinationRepository.findById(coordinationId).orElse(null);
    if (coordination == null) {
      return Map.of();
    }
    List<ItemRow> rows = itemRepository.listByCoordination(coordinationId);
    return stateService.states(coordination, rows);
  }

  private String officeName(Map<Long, String> cache, Long officeId) {
    return cache.computeIfAbsent(
        officeId,
        key -> officeDirectory.find(key).map(OfficeDirectory.OfficeSummary::name).orElse(""));
  }
}
