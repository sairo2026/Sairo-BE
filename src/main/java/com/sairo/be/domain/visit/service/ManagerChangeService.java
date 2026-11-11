package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.property.service.ManagingMemberDirectory;
import com.sairo.be.domain.property.service.ManagingMemberDirectory.ManagingMember;
import com.sairo.be.domain.visit.dto.response.VisitCoordinationDetailResponse;
import com.sairo.be.domain.visit.dto.response.VisitManagerUpdateResponse;
import com.sairo.be.domain.visit.entity.CoordinationStatus;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.repository.ScheduleBusyRepository;
import com.sairo.be.domain.visit.repository.ScheduleBusyRepository.BusyVisit;
import com.sairo.be.domain.visit.repository.ScheduleItemRepository;
import com.sairo.be.domain.visit.repository.ScheduleItemRepository.ItemRow;
import com.sairo.be.domain.visit.repository.VisitCoordinationRepository;
import com.sairo.be.domain.visit.repository.VisitCustomerLinkRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.port.VisitWorkHooks;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StaffRole;
import com.sairo.be.global.support.AdvisoryLock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class ManagerChangeService {

  private static final Set<String> KEYS = Set.of("managerMembershipId");

  private final CoordinationAccess access;
  private final ManagingMemberDirectory memberDirectory;
  private final ScheduleItemRepository itemRepository;
  private final ScheduleBusyRepository busyRepository;
  private final VisitCustomerLinkRepository linkRepository;
  private final VisitCoordinationRepository coordinationRepository;
  private final VisitCoordinationQueryService queryService;
  private final AdvisoryLock advisoryLock;
  private final VisitWorkHooks workHooks;
  private final Clock clock;

  public ManagerChangeService(
      CoordinationAccess access,
      ManagingMemberDirectory memberDirectory,
      ScheduleItemRepository itemRepository,
      ScheduleBusyRepository busyRepository,
      VisitCustomerLinkRepository linkRepository,
      VisitCoordinationRepository coordinationRepository,
      VisitCoordinationQueryService queryService,
      AdvisoryLock advisoryLock,
      VisitWorkHooks workHooks,
      Clock clock) {
    this.access = access;
    this.memberDirectory = memberDirectory;
    this.itemRepository = itemRepository;
    this.busyRepository = busyRepository;
    this.linkRepository = linkRepository;
    this.coordinationRepository = coordinationRepository;
    this.queryService = queryService;
    this.advisoryLock = advisoryLock;
    this.workHooks = workHooks;
    this.clock = clock;
  }

  @Transactional
  public VisitManagerUpdateResponse change(
      StaffPrincipal principal, Long coordinationId, long expectedVersion, JsonNode body) {
    CustomerInput.requireObject(body, KEYS);
    Long newManagerId = CustomerInput.requiredLong(body, "managerMembershipId");
    VisitCoordination preview =
        coordinationRepository
            .findByIdAndOfficeId(coordinationId, principal.officeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (principal.role() == StaffRole.DEPUTY) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    ManagingMember member =
        memberDirectory
            .find(newManagerId)
            .filter(found -> found.officeId().equals(principal.officeId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!member.canManageProperty()) {
      throw CustomerInput.invalid(
          "managerMembershipId", FieldErrorCode.INVALID_VALUE, "승인된 대표 또는 소속공인중개사만 담당자가 될 수 있습니다.");
    }
    Long finalCustomer = busyRepository.rootCustomer(preview.getCustomerId());
    List<String> keys = new ArrayList<>();
    keys.add(ScheduleLockKeys.manager(principal.officeId(), newManagerId));
    keys.add(ScheduleLockKeys.customer(principal.officeId(), finalCustomer));
    advisoryLock.lockAllForTransaction(keys);
    VisitCoordination coordination = access.lock(principal, coordinationId);
    if (coordination.getVersion() != expectedVersion) {
      throw BusinessException.versionConflict(coordination.getVersion());
    }
    CoordinationStatus status = coordination.getStatus();
    if (status == CoordinationStatus.VISIT_COMPLETED
        || status == CoordinationStatus.VISIT_INCOMPLETE
        || status == CoordinationStatus.CANCELLED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant now = clock.instant();
    List<ItemRow> own =
        itemRepository.listByCoordination(coordinationId).stream()
            .filter(row -> "SCHEDULED".equals(row.result()))
            .toList();
    if (!coordination.getManagerMembershipId().equals(newManagerId)) {
      List<BusyVisit> newManagerBusy = busyRepository.forManager(newManagerId);
      for (ItemRow row : own) {
        int travel =
            row.travelEndsAt() == null
                ? 0
                : (int) Duration.between(row.endAt(), row.travelEndsAt()).toMinutes();
        if (!VisitFeasibility.busyHits(
                row.startAt(), row.endAt(), travel, newManagerBusy, List.of(), List.of())
            .isEmpty()) {
          throw new BusinessException(ErrorCode.SCHEDULE_CONFLICT);
        }
      }
      coordination.changeManager(newManagerId, now);
      coordinationRepository.saveAndFlush(coordination);
      linkRepository
          .findFirstByCoordinationIdAndRevokedAtIsNull(coordinationId)
          .ifPresent(link -> link.revoke(now));
      workHooks.coordinationManagerChanged(coordinationId, principal.membershipId());
    }
    List<VisitManagerUpdateResponse.AffectedItem> affected = new ArrayList<>();
    List<ItemRow> kept = own.stream().filter(ItemRow::kept).toList();
    for (int index = 0; index + 1 < kept.size(); index++) {
      ItemRow current = kept.get(index);
      ItemRow next = kept.get(index + 1);
      if (current.travelEndsAt() != null && current.travelEndsAt().isAfter(next.startAt())) {
        affected.add(
            new VisitManagerUpdateResponse.AffectedItem(
                next.id(),
                next.candidatePropertyId(),
                "RESCHEDULE_REQUIRED",
                VisitFeasibility.TRAVEL_CONFLICT));
      }
    }
    VisitCoordinationDetailResponse detail = queryService.detail(principal, coordinationId, null);
    return new VisitManagerUpdateResponse(detail, affected);
  }
}
