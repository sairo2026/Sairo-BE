package com.sairo.be.domain.coordination.service;

import com.sairo.be.domain.coordination.dto.response.CoordinationCancelResponse;
import com.sairo.be.domain.coordination.entity.Coordination;
import com.sairo.be.domain.coordination.entity.CoordinationStatus;
import com.sairo.be.domain.coordination.entity.CoordinationStatusHistory;
import com.sairo.be.domain.coordination.repository.CoordinationRepository;
import com.sairo.be.domain.coordination.repository.CoordinationStatusHistoryRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class CoordinationCancelService {

  private final CoordinationRepository coordinationRepository;
  private final CoordinationStatusHistoryRepository statusHistoryRepository;

  public CoordinationCancelService(
      CoordinationRepository coordinationRepository,
      CoordinationStatusHistoryRepository statusHistoryRepository) {
    this.coordinationRepository = coordinationRepository;
    this.statusHistoryRepository = statusHistoryRepository;
  }

  @Transactional
  public CoordinationCancelResponse cancel(Long officeId, Long membershipId, Long coordinationId) {
    Coordination coordination =
        coordinationRepository
            .findByIdAndOfficeIdForUpdate(coordinationId, officeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

    boolean isActive =
        coordination.getStatus() == CoordinationStatus.TENANT_CHECKING
            || coordination.getStatus() == CoordinationStatus.BUYER_DELIVERY_REQUIRED
            || coordination.getStatus() == CoordinationStatus.BUYER_CHECKING
            || coordination.getStatus() == CoordinationStatus.FINAL_CONFIRMATION_REQUIRED;
    if (!isActive) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }

    CoordinationStatus previousStatus = coordination.getStatus();
    coordination.cancel(Instant.now());

    statusHistoryRepository.save(
        CoordinationStatusHistory.staffTransition(
            coordinationId, previousStatus, coordination.getStatus(), membershipId));

    return new CoordinationCancelResponse(coordination.getStatus());
  }
}
