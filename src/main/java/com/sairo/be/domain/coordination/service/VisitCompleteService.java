package com.sairo.be.domain.coordination.service;

import com.sairo.be.domain.coordination.dto.response.VisitCompleteResponse;
import com.sairo.be.domain.coordination.entity.Coordination;
import com.sairo.be.domain.coordination.entity.CoordinationStatus;
import com.sairo.be.domain.coordination.entity.CoordinationStatusHistory;
import com.sairo.be.domain.coordination.repository.CoordinationRepository;
import com.sairo.be.domain.coordination.repository.CoordinationStatusHistoryRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class VisitCompleteService {

  private final CoordinationRepository coordinationRepository;
  private final CoordinationStatusHistoryRepository statusHistoryRepository;

  public VisitCompleteService(
      CoordinationRepository coordinationRepository,
      CoordinationStatusHistoryRepository statusHistoryRepository) {
    this.coordinationRepository = coordinationRepository;
    this.statusHistoryRepository = statusHistoryRepository;
  }

  @Transactional
  public VisitCompleteResponse complete(Long officeId, Long membershipId, Long coordinationId) {
    Coordination coordination =
        coordinationRepository
            .findByIdAndOfficeIdForUpdate(coordinationId, officeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

    if (coordination.getStatus() != CoordinationStatus.SCHEDULE_CONFIRMED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }

    CoordinationStatus previousStatus = coordination.getStatus();
    coordination.completeVisit();

    statusHistoryRepository.save(
        CoordinationStatusHistory.staffTransition(
            coordinationId, previousStatus, coordination.getStatus(), membershipId));

    return new VisitCompleteResponse(coordination.getStatus());
  }
}
