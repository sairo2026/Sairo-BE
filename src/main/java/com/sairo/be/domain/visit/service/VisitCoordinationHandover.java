package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.visit.entity.CoordinationStatus;
import com.sairo.be.domain.visit.entity.RescheduleTriggerType;
import com.sairo.be.domain.visit.entity.StatusChangeSource;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.repository.ScheduleBusyRepository;
import com.sairo.be.domain.visit.repository.ScheduleBusyRepository.BusyVisit;
import com.sairo.be.domain.visit.repository.ScheduleItemRepository;
import com.sairo.be.domain.visit.repository.ScheduleItemRepository.ItemRow;
import com.sairo.be.domain.visit.repository.VisitCoordinationRepository;
import com.sairo.be.domain.visit.repository.VisitCustomerLinkRepository;
import com.sairo.be.global.port.MemberWorkHandover;
import com.sairo.be.global.port.VisitWorkHooks;
import com.sairo.be.global.support.AdvisoryLock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 끝나지 않은 방문 조율의 담당자를 대표에게 넘긴다(MEMBER-05, SETTING-02 ③). 확정 방문은 자동으로 다시 확정하지 않고 대표의 다른 확정 방문과 겹치는지
 * 검사해 겹치면 재조율 필요로 표시한다. 결과 이력과 신청자는 바꾸지 않는다.
 */
@Profile("!migrate")
@Component
public class VisitCoordinationHandover implements MemberWorkHandover {

  static final String CONFLICT_REASON = "담당자 인계 뒤 새 담당자의 일정과 겹침";

  private final JdbcClient jdbcClient;
  private final VisitCoordinationRepository coordinationRepository;
  private final ScheduleItemRepository itemRepository;
  private final ScheduleBusyRepository busyRepository;
  private final VisitCustomerLinkRepository linkRepository;
  private final CoordinationStatusService statusService;
  private final AdvisoryLock advisoryLock;
  private final VisitWorkHooks workHooks;

  public VisitCoordinationHandover(
      JdbcClient jdbcClient,
      VisitCoordinationRepository coordinationRepository,
      ScheduleItemRepository itemRepository,
      ScheduleBusyRepository busyRepository,
      VisitCustomerLinkRepository linkRepository,
      CoordinationStatusService statusService,
      AdvisoryLock advisoryLock,
      VisitWorkHooks workHooks) {
    this.jdbcClient = jdbcClient;
    this.coordinationRepository = coordinationRepository;
    this.itemRepository = itemRepository;
    this.busyRepository = busyRepository;
    this.linkRepository = linkRepository;
    this.statusService = statusService;
    this.advisoryLock = advisoryLock;
    this.workHooks = workHooks;
  }

  @Override
  public WorkKind kind() {
    return WorkKind.VISIT;
  }

  /** 끝나지 않은 조율의 확정 예정 방문 수다. */
  @Override
  public long countOpen(Long officeId, Long membershipId) {
    return jdbcClient
        .sql(
            "SELECT count(*) FROM visit_schedule_item i JOIN visit_coordination c ON c.id ="
                + " i.coordination_id WHERE c.office_id = :office AND c.manager_membership_id ="
                + " :member AND c.status NOT IN ('VISIT_COMPLETED','VISIT_INCOMPLETE','CANCELLED')"
                + " AND i.result = 'SCHEDULED' AND i.confirmed_request_id IS NOT NULL")
        .param("office", officeId)
        .param("member", membershipId)
        .query(Long.class)
        .single();
  }

  @Override
  public int handOver(Long officeId, Long fromMembershipId, Long toMembershipId, Instant now) {
    List<Long> ids =
        jdbcClient
            .sql(
                "SELECT id FROM visit_coordination WHERE office_id = :office AND"
                    + " manager_membership_id = :member AND status NOT IN"
                    + " ('VISIT_COMPLETED','VISIT_INCOMPLETE','CANCELLED') ORDER BY id")
            .param("office", officeId)
            .param("member", fromMembershipId)
            .query(Long.class)
            .list();
    if (ids.isEmpty()) {
      return 0;
    }
    advisoryLock.lockForTransaction(ScheduleLockKeys.manager(officeId, toMembershipId));
    int changed = 0;
    for (Long id : ids) {
      VisitCoordination coordination =
          coordinationRepository.findForUpdate(id, officeId).orElse(null);
      if (coordination == null
          || !fromMembershipId.equals(coordination.getManagerMembershipId())
          || coordination.getStatus().isFinished()) {
        continue;
      }
      boolean conflict = conflictsWithNewManager(id, toMembershipId);
      coordination.changeManager(toMembershipId, now);
      if (conflict && coordination.getStatus() == CoordinationStatus.CONFIRMED) {
        statusService.raiseReschedule(
            coordination,
            RescheduleTriggerType.ASSIGNEE_HANDOVER,
            StatusChangeSource.SYSTEM,
            null,
            CONFLICT_REASON);
      }
      coordinationRepository.saveAndFlush(coordination);
      linkRepository
          .findFirstByCoordinationIdAndRevokedAtIsNull(id)
          .ifPresent(link -> link.revoke(now));
      workHooks.coordinationManagerChanged(id, fromMembershipId);
      changed++;
    }
    return changed;
  }

  private boolean conflictsWithNewManager(Long coordinationId, Long newManagerId) {
    List<ItemRow> own =
        itemRepository.listByCoordination(coordinationId).stream()
            .filter(row -> "SCHEDULED".equals(row.result()))
            .filter(row -> row.startAt() != null && row.endAt() != null)
            .toList();
    if (own.isEmpty()) {
      return false;
    }
    List<BusyVisit> busy = busyRepository.forManager(newManagerId);
    for (ItemRow row : own) {
      int travel =
          row.travelEndsAt() == null
              ? 0
              : (int) Duration.between(row.endAt(), row.travelEndsAt()).toMinutes();
      if (!VisitFeasibility.busyHits(row.startAt(), row.endAt(), travel, busy, List.of(), List.of())
          .isEmpty()) {
        return true;
      }
    }
    return false;
  }
}
