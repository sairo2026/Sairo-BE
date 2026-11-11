package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.visit.entity.CoordinationStatus;
import com.sairo.be.domain.visit.entity.RequestNoticeType;
import com.sairo.be.domain.visit.entity.RequestStatus;
import com.sairo.be.domain.visit.entity.RequestStatusSource;
import com.sairo.be.domain.visit.entity.StatusChangeSource;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.entity.VisitRequest;
import com.sairo.be.domain.visit.entity.VisitRequestNotice;
import com.sairo.be.domain.visit.repository.ScheduleItemRepository;
import com.sairo.be.domain.visit.repository.ScheduleItemRepository.ItemRow;
import com.sairo.be.domain.visit.repository.ScheduleResultRepository;
import com.sairo.be.domain.visit.repository.ScheduleResultRepository.ClosedRequest;
import com.sairo.be.domain.visit.repository.ScheduleResultRepository.HistoryInsert;
import com.sairo.be.domain.visit.repository.ScheduleResultRepository.ItemHead;
import com.sairo.be.domain.visit.repository.VisitCustomerLinkRepository;
import com.sairo.be.domain.visit.repository.VisitRequestNoticeRepository;
import com.sairo.be.domain.visit.repository.VisitRequestRepository;
import com.sairo.be.domain.visit.repository.VisitRequestStatusHistoryRepository;
import com.sairo.be.domain.visit.service.VisitRequestChecker.Slot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 기능명세서 VISIT-09의 확정 취소 절차와 조율 건 집계 상태 재계산을 한 곳에서 처리한다. */
@Profile("!migrate")
@Component
public class ScheduleOutcomeService {

  public static final String USER_CANCEL = "USER_CANCEL";
  public static final String CHANGE_REPLACED = "CHANGE_REPLACED";

  private final ScheduleResultRepository resultRepository;
  private final ScheduleItemRepository itemRepository;
  private final VisitRequestRepository requestRepository;
  private final VisitRequestStatusHistoryRepository historyRepository;
  private final VisitRequestNoticeRepository noticeRepository;
  private final VisitCustomerLinkRepository linkRepository;
  private final CoordinationStatusService statusService;
  private final ScheduleStateService stateService;

  public ScheduleOutcomeService(
      ScheduleResultRepository resultRepository,
      ScheduleItemRepository itemRepository,
      VisitRequestRepository requestRepository,
      VisitRequestStatusHistoryRepository historyRepository,
      VisitRequestNoticeRepository noticeRepository,
      VisitCustomerLinkRepository linkRepository,
      CoordinationStatusService statusService,
      ScheduleStateService stateService) {
    this.resultRepository = resultRepository;
    this.itemRepository = itemRepository;
    this.requestRepository = requestRepository;
    this.historyRepository = historyRepository;
    this.noticeRepository = noticeRepository;
    this.linkRepository = linkRepository;
    this.statusService = statusService;
    this.stateService = stateService;
  }

  /** 예정 방문 하나를 취소한다. replacement가 있으면 변경 대체이며 새 확정 구간과 겹치지 않는 부분만 빈 구간으로 본다. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void cancelItem(
      ItemHead item,
      String reasonCode,
      Long recorderMembershipId,
      RequestStatusSource source,
      Long actorMembershipId,
      Instant now,
      Slot replacement) {
    resultRepository.insertHistory(
        new HistoryInsert(
            item.id(),
            "SCHEDULED",
            "CANCELLED",
            null,
            null,
            now,
            now,
            recorderMembershipId,
            null,
            null,
            reasonCode));
    resultRepository.updateItem(
        item.id(), "CANCELLED", now, null, recorderMembershipId, null, null);
    if (item.confirmedRequestId() == null) {
      return;
    }
    requestRepository
        .findForUpdate(item.confirmedRequestId())
        .filter(request -> request.getStatus() == RequestStatus.CONFIRMED)
        .ifPresent(
            request -> {
              historyRepository.save(
                  request.moveTo(
                      RequestStatus.CANCELLED, source, actorMembershipId, reasonCode, now));
              requestRepository.saveAndFlush(request);
              reopenNotices(item, now, replacement);
            });
  }

  private void reopenNotices(ItemHead item, Instant now, Slot replacement) {
    if (item.startAt() == null || item.endAt() == null) {
      return;
    }
    List<Slot> freed = new ArrayList<>();
    if (replacement == null
        || !replacement.startsAt().isBefore(item.endAt())
        || !item.startAt().isBefore(replacement.endsAt())) {
      freed.add(new Slot(item.startAt(), item.endAt()));
    } else {
      if (item.startAt().isBefore(replacement.startsAt())) {
        freed.add(new Slot(item.startAt(), replacement.startsAt()));
      }
      if (replacement.endsAt().isBefore(item.endAt())) {
        freed.add(new Slot(replacement.endsAt(), item.endAt()));
      }
    }
    Set<Long> notified = new LinkedHashSet<>();
    for (Slot slot : freed) {
      for (ClosedRequest closed :
          resultRepository.timeClosedOverlapping(
              item.propertyId(), slot.startsAt(), slot.endsAt())) {
        if (notified.add(closed.requestId())) {
          noticeRepository.save(
              VisitRequestNotice.of(
                  closed.requestId(),
                  closed.applicantMembershipId(),
                  RequestNoticeType.SLOT_REOPENED,
                  now));
        }
      }
    }
  }

  /** 조율 건의 신청 대기·선택됨 신청을 철회로 종결한다. 집계 상태는 호출한 쪽이 정한다. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void withdrawActive(VisitCoordination coordination, Long actorMembershipId, Instant now) {
    for (VisitRequest preview :
        requestRepository.findByCoordinationIdOrderBySubmittedAtDescIdDesc(coordination.getId())) {
      if (!preview.getStatus().isActive()) {
        continue;
      }
      VisitRequest request = requestRepository.findForUpdate(preview.getId()).orElse(null);
      if (request == null || !request.getStatus().isActive()) {
        continue;
      }
      historyRepository.save(
          request.moveTo(
              RequestStatus.WITHDRAWN,
              RequestStatusSource.BROKER,
              actorMembershipId,
              "조율 취소",
              now));
      requestRepository.saveAndFlush(request);
    }
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void revokeCustomerLink(Long coordinationId, Instant now) {
    linkRepository
        .findFirstByCoordinationIdAndRevokedAtIsNull(coordinationId)
        .ifPresent(link -> link.revoke(now));
  }

  /** 예정 방문이 남지 않았거나 재조율 영향이 모두 해소됐으면 조율 건 집계 상태를 옮긴다. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void settle(
      VisitCoordination coordination,
      StatusChangeSource source,
      Long actorMembershipId,
      String reason) {
    CoordinationStatus status = coordination.getStatus();
    boolean concluded =
        status == CoordinationStatus.VISIT_COMPLETED
            || status == CoordinationStatus.VISIT_INCOMPLETE;
    if (status != CoordinationStatus.CONFIRMED
        && status != CoordinationStatus.RESCHEDULE_REQUIRED
        && !concluded) {
      return;
    }
    List<ItemRow> rows = itemRepository.listByCoordination(coordination.getId());
    if (rows.isEmpty()) {
      return;
    }
    boolean anyScheduled = rows.stream().anyMatch(row -> "SCHEDULED".equals(row.result()));
    if (!anyScheduled) {
      boolean completed = rows.stream().anyMatch(row -> "COMPLETED".equals(row.result()));
      CoordinationStatus next =
          completed ? CoordinationStatus.VISIT_COMPLETED : CoordinationStatus.VISIT_INCOMPLETE;
      if (next != status) {
        statusService.move(coordination, next, source, actorMembershipId, reason);
      }
      return;
    }
    if (status == CoordinationStatus.RESCHEDULE_REQUIRED
        && !stateService.hasAffected(stateService.states(coordination, rows))) {
      statusService.move(
          coordination,
          CoordinationStatus.CONFIRMED,
          source,
          actorMembershipId,
          "영향 방문이 모두 해소되었다.");
    }
  }
}
