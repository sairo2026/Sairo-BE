package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.visit.entity.CoordinationStatus;
import com.sairo.be.domain.visit.entity.RescheduleTriggerType;
import com.sairo.be.domain.visit.entity.StatusChangeSource;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.entity.VisitCoordinationStatusHistory;
import com.sairo.be.domain.visit.repository.VisitCoordinationStatusHistoryRepository;
import java.time.Clock;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class CoordinationStatusService {

  private final VisitCoordinationStatusHistoryRepository historyRepository;
  private final Clock clock;

  public CoordinationStatusService(
      VisitCoordinationStatusHistoryRepository historyRepository, Clock clock) {
    this.historyRepository = historyRepository;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void recordOpening(
      VisitCoordination coordination, StatusChangeSource source, Long actorMembershipId) {
    historyRepository.save(coordination.openingHistory(actorMembershipId, source, clock.instant()));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void raiseReschedule(
      VisitCoordination coordination,
      RescheduleTriggerType trigger,
      StatusChangeSource source,
      Long actorMembershipId,
      String reason) {
    historyRepository.save(
        coordination.raiseReschedule(trigger, reason, source, actorMembershipId, clock.instant()));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void raiseAccessRevoked(
      VisitCoordination coordination, Long approvalId, Long actorMembershipId, String reason) {
    historyRepository.save(
        coordination.raiseReschedule(
            RescheduleTriggerType.ACCESS_REVOKED,
            reason,
            approvalId,
            StatusChangeSource.STAFF,
            actorMembershipId,
            clock.instant()));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void move(
      VisitCoordination coordination,
      CoordinationStatus next,
      StatusChangeSource source,
      Long actorMembershipId,
      String reason) {
    VisitCoordinationStatusHistory history =
        coordination.moveTo(next, source, actorMembershipId, reason, clock.instant());
    historyRepository.save(history);
  }
}
