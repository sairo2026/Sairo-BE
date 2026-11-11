package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.property.service.PropertyReferenceService;
import com.sairo.be.domain.visit.dto.response.ScheduleConflictResponse;
import com.sairo.be.domain.visit.entity.RequestStatus;
import com.sairo.be.domain.visit.entity.VisitCandidateProperty;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.entity.VisitRequest;
import com.sairo.be.domain.visit.repository.ScheduleBusyRepository;
import com.sairo.be.domain.visit.repository.ScheduleBusyRepository.BusyVisit;
import com.sairo.be.domain.visit.repository.ScheduleItemRepository;
import com.sairo.be.domain.visit.repository.ScheduleItemRepository.ItemRow;
import com.sairo.be.domain.visit.repository.VisitCandidatePropertyRepository;
import com.sairo.be.domain.visit.repository.VisitCoordinationRepository;
import com.sairo.be.domain.visit.repository.VisitRequestRepository;
import com.sairo.be.domain.visit.service.VisitFeasibility.Hit;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StaffRole;
import com.sairo.be.global.support.TimeRules;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
public class ScheduleConflictQueryService {

  private final CoordinationAccess access;
  private final VisitCandidatePropertyRepository candidateRepository;
  private final VisitRequestRepository requestRepository;
  private final ScheduleItemRepository itemRepository;
  private final ScheduleBusyRepository busyRepository;
  private final FeasibilityContextFactory contextFactory;
  private final VisitCoordinationRepository coordinationRepository;
  private final PropertyReferenceService propertyReferences;

  public ScheduleConflictQueryService(
      CoordinationAccess access,
      VisitCandidatePropertyRepository candidateRepository,
      VisitRequestRepository requestRepository,
      ScheduleItemRepository itemRepository,
      ScheduleBusyRepository busyRepository,
      FeasibilityContextFactory contextFactory,
      VisitCoordinationRepository coordinationRepository,
      PropertyReferenceService propertyReferences) {
    this.access = access;
    this.candidateRepository = candidateRepository;
    this.requestRepository = requestRepository;
    this.itemRepository = itemRepository;
    this.busyRepository = busyRepository;
    this.contextFactory = contextFactory;
    this.coordinationRepository = coordinationRepository;
    this.propertyReferences = propertyReferences;
  }

  public ScheduleConflictResponse conflicts(StaffPrincipal principal, Long coordinationId) {
    VisitCoordination coordination = access.view(principal, coordinationId);
    List<VisitCandidateProperty> candidates =
        candidateRepository.findByCoordinationIdOrderBySortOrderAscIdAsc(coordinationId);
    List<BusyVisit> manager = busyRepository.forManager(coordination.getManagerMembershipId());
    List<BusyVisit> customer =
        busyRepository.forCustomers(contextFactory.customerGroup(coordination));
    Map<Long, ItemRow> ownItems = new HashMap<>();
    for (ItemRow row : itemRepository.listByCoordination(coordinationId)) {
      if ("SCHEDULED".equals(row.result())) {
        ownItems.put(row.candidatePropertyId(), row);
      }
    }
    List<VisitRequest> pendingRequests =
        requestRepository.findByCoordinationIdAndStatusOrderBySubmittedAtDescIdDesc(
            coordinationId, RequestStatus.PENDING);
    Map<Long, Optional<Boolean>> visibleCoordinations = new HashMap<>();
    List<ScheduleConflictResponse.Conflict> result = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (int index = 0; index < candidates.size(); index++) {
      VisitCandidateProperty candidate = candidates.get(index);
      Instant start;
      Instant end;
      Long ownItemId = null;
      Optional<VisitRequest> pending =
          pendingRequests.stream()
              .filter(request -> request.getCandidatePropertyId().equals(candidate.getId()))
              .findFirst();
      if (ownItems.containsKey(candidate.getId())) {
        ItemRow row = ownItems.get(candidate.getId());
        start = row.startAt();
        end = row.endAt();
        ownItemId = row.id();
      } else if (pending.isPresent()) {
        start = pending.get().getRequestedStartAt();
        end = pending.get().getRequestedEndAt();
      } else {
        continue;
      }
      int travel = index == candidates.size() - 1 ? 0 : candidate.getTravelDurationMinutes();
      final Long ignored = ownItemId;
      List<Hit> hits =
          VisitFeasibility.busyHits(
              start,
              end,
              travel,
              manager.stream().filter(b -> !b.itemId().equals(ignored)).toList(),
              customer.stream().filter(b -> !b.itemId().equals(ignored)).toList(),
              busyRepository.forProperty(candidate.getPropertyId()).stream()
                  .filter(b -> !b.itemId().equals(ignored))
                  .toList());
      for (Hit hit : hits) {
        String key = candidate.getId() + ":" + hit.kind() + ":" + hit.busy().itemId();
        if (!seen.add(key)) {
          continue;
        }
        boolean visible = visible(principal, hit.busy(), visibleCoordinations);
        result.add(
            new ScheduleConflictResponse.Conflict(
                candidate.getId(),
                hit.kind().name(),
                hit.busy().itemId(),
                visible ? hit.busy().coordinationId() : null,
                TimeRules.toDisplayOffset(hit.busy().startAt()),
                TimeRules.toDisplayOffset(hit.busy().endAt()),
                visible
                    ? propertyReferences
                        .find(hit.busy().propertyId())
                        .map(PropertyReferenceService.PropertySummary::name)
                        .orElse(null)
                    : null));
      }
    }
    return new ScheduleConflictResponse(result);
  }

  private boolean visible(
      StaffPrincipal principal, BusyVisit busy, Map<Long, Optional<Boolean>> cache) {
    if (!busy.officeId().equals(principal.officeId())) {
      return false;
    }
    if (principal.role() != StaffRole.DEPUTY) {
      return true;
    }
    return cache
        .computeIfAbsent(
            busy.coordinationId(),
            id ->
                coordinationRepository
                    .findById(id)
                    .map(c -> c.getManagerMembershipId().equals(principal.membershipId())))
        .orElse(false);
  }
}
