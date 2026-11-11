package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.visit.entity.CoordinationStatus;
import com.sairo.be.domain.visit.entity.RequestKind;
import com.sairo.be.domain.visit.entity.RequestStatus;
import com.sairo.be.domain.visit.entity.RequestStatusSource;
import com.sairo.be.domain.visit.entity.StatusChangeSource;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.entity.VisitRequest;
import com.sairo.be.domain.visit.repository.VisitRequestRepository;
import com.sairo.be.domain.visit.repository.VisitRequestStatusHistoryRepository;
import java.time.Clock;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Component
public class VisitRequestStatusService {

  private final VisitRequestRepository requestRepository;
  private final VisitRequestStatusHistoryRepository historyRepository;
  private final CoordinationStatusService coordinationStatus;
  private final Clock clock;

  public VisitRequestStatusService(
      VisitRequestRepository requestRepository,
      VisitRequestStatusHistoryRepository historyRepository,
      CoordinationStatusService coordinationStatus,
      Clock clock) {
    this.requestRepository = requestRepository;
    this.historyRepository = historyRepository;
    this.coordinationStatus = coordinationStatus;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void close(
      VisitRequest request,
      VisitCoordination coordination,
      RequestStatus next,
      RequestStatusSource source,
      Long actorMembershipId,
      String reason) {
    historyRepository.save(
        request.moveTo(next, source, actorMembershipId, reason, clock.instant()));
    requestRepository.saveAndFlush(request);
    reopenIfNoActive(coordination, source, actorMembershipId, reason);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void reopenIfNoActive(
      VisitCoordination coordination,
      RequestStatusSource source,
      Long actorMembershipId,
      String reason) {
    if (coordination.getStatus() != CoordinationStatus.APPLYING) {
      return;
    }
    long active =
        requestRepository.countByCoordinationIdAndRequestKindAndStatusIn(
            coordination.getId(), RequestKind.NEW, RequestStatus.ACTIVE);
    if (active == 0) {
      coordinationStatus.move(
          coordination,
          CoordinationStatus.FINAL_CONFIRMATION_REQUIRED,
          changeSource(source),
          actorMembershipId,
          reason == null ? "신청이 모두 종료되어 새 신청을 받는다." : "신청 종료: " + reason);
    }
  }

  private static StatusChangeSource changeSource(RequestStatusSource source) {
    return switch (source) {
      case BROKER -> StatusChangeSource.STAFF;
      case APPROVER -> StatusChangeSource.APPROVER;
      case SYSTEM -> StatusChangeSource.SYSTEM;
    };
  }
}
