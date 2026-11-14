package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.visit.entity.RequestNoticeType;
import com.sairo.be.domain.visit.entity.RequestStatus;
import com.sairo.be.domain.visit.entity.RequestStatusSource;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.entity.VisitRequest;
import com.sairo.be.domain.visit.entity.VisitRequestNotice;
import com.sairo.be.domain.visit.repository.VisitCoordinationRepository;
import com.sairo.be.domain.visit.repository.VisitRequestNoticeRepository;
import com.sairo.be.domain.visit.repository.VisitRequestReadRepository;
import com.sairo.be.domain.visit.repository.VisitRequestReadRepository.OverdueRow;
import com.sairo.be.domain.visit.repository.VisitRequestRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Component
public class VisitRequestExpiryProcessor {

  private final VisitCoordinationRepository coordinationRepository;
  private final VisitRequestRepository requestRepository;
  private final VisitRequestReadRepository readRepository;
  private final VisitRequestNoticeRepository noticeRepository;
  private final VisitRequestStatusService statusService;
  private final Clock clock;

  public VisitRequestExpiryProcessor(
      VisitCoordinationRepository coordinationRepository,
      VisitRequestRepository requestRepository,
      VisitRequestReadRepository readRepository,
      VisitRequestNoticeRepository noticeRepository,
      VisitRequestStatusService statusService,
      Clock clock) {
    this.coordinationRepository = coordinationRepository;
    this.requestRepository = requestRepository;
    this.readRepository = readRepository;
    this.noticeRepository = noticeRepository;
    this.statusService = statusService;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean expire(Long requestId, Long coordinationId) {
    Optional<VisitCoordination> coordination =
        coordinationRepository.findForUpdateById(coordinationId);
    Optional<VisitRequest> request = requestRepository.findForUpdate(requestId);
    if (coordination.isEmpty() || request.isEmpty()) {
      return false;
    }
    if (request.get().getStatus() != RequestStatus.PENDING) {
      return false;
    }
    Instant now = clock.instant();
    OverdueRow row = readRepository.findOverdueById(requestId, now);
    if (row == null) {
      return false;
    }
    statusService.close(
        request.get(),
        coordination.get(),
        RequestStatus.EXPIRED,
        RequestStatusSource.SYSTEM,
        null,
        row.reason());
    if (row.reason().startsWith("GRANT_")) {
      noticeRepository.save(
          VisitRequestNotice.of(
              requestId,
              request.get().getApplicantMembershipId(),
              RequestNoticeType.GRANT_REVOKED,
              now));
    }
    return true;
  }
}
