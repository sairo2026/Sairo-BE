package com.sairo.be.domain.coordination.service;

import com.sairo.be.domain.coordination.dto.request.CoordinationConfirmRequest;
import com.sairo.be.domain.coordination.dto.response.CoordinationConfirmResponse;
import com.sairo.be.domain.coordination.entity.Coordination;
import com.sairo.be.domain.coordination.entity.CoordinationCandidateTime;
import com.sairo.be.domain.coordination.entity.CoordinationCustomerResponse;
import com.sairo.be.domain.coordination.entity.CoordinationStatus;
import com.sairo.be.domain.coordination.entity.CoordinationStatusHistory;
import com.sairo.be.domain.coordination.entity.CustomerResponseCandidate;
import com.sairo.be.domain.coordination.entity.CustomerResponseCandidateId;
import com.sairo.be.domain.coordination.entity.CustomerResponseResult;
import com.sairo.be.domain.coordination.entity.CustomerResponseRole;
import com.sairo.be.domain.coordination.repository.CoordinationCandidateTimeRepository;
import com.sairo.be.domain.coordination.repository.CoordinationCustomerResponseRepository;
import com.sairo.be.domain.coordination.repository.CoordinationRepository;
import com.sairo.be.domain.coordination.repository.CoordinationStatusHistoryRepository;
import com.sairo.be.domain.coordination.repository.CustomerResponseCandidateRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class CoordinationConfirmationService {

  private final CoordinationRepository coordinationRepository;
  private final CoordinationCustomerResponseRepository responseRepository;
  private final CustomerResponseCandidateRepository responseCandidateRepository;
  private final CoordinationCandidateTimeRepository candidateTimeRepository;
  private final CoordinationStatusHistoryRepository statusHistoryRepository;

  public CoordinationConfirmationService(
      CoordinationRepository coordinationRepository,
      CoordinationCustomerResponseRepository responseRepository,
      CustomerResponseCandidateRepository responseCandidateRepository,
      CoordinationCandidateTimeRepository candidateTimeRepository,
      CoordinationStatusHistoryRepository statusHistoryRepository) {
    this.coordinationRepository = coordinationRepository;
    this.responseRepository = responseRepository;
    this.responseCandidateRepository = responseCandidateRepository;
    this.candidateTimeRepository = candidateTimeRepository;
    this.statusHistoryRepository = statusHistoryRepository;
  }

  @Transactional
  public CoordinationConfirmResponse confirm(
      Long officeId, Long membershipId, Long coordinationId, CoordinationConfirmRequest request) {
    Coordination coordination =
        coordinationRepository
            .findByIdAndOfficeIdForUpdate(coordinationId, officeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

    if (coordination.getStatus() != CoordinationStatus.FINAL_CONFIRMATION_REQUIRED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }

    CoordinationCustomerResponse selectedBuyer =
        responseRepository
            .findById(request.buyerResponseId())
            .filter(
                response ->
                    response.getCoordinationId().equals(coordinationId)
                        && response.getRole() == CustomerResponseRole.BUYER)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

    if (selectedBuyer.getResult() != CustomerResponseResult.AVAILABLE_SUBMITTED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }

    responseCandidateRepository
        .findById(new CustomerResponseCandidateId(selectedBuyer.getId(), request.candidateTimeId()))
        .filter(CustomerResponseCandidate::isSelected)
        .orElseThrow(() -> new BusinessException(ErrorCode.CANDIDATE_NOT_ALLOWED));

    CoordinationCandidateTime candidateTime =
        candidateTimeRepository
            .findById(request.candidateTimeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

    Instant confirmedAt = Instant.now();
    selectedBuyer.confirmSelection();

    responseRepository
        .findAllByCoordinationIdAndRole(coordinationId, CustomerResponseRole.BUYER)
        .stream()
        .filter(response -> !response.getId().equals(selectedBuyer.getId()))
        .forEach(CoordinationCustomerResponse::markNotSelected);

    CoordinationStatus previousStatus = coordination.getStatus();
    coordination.confirmSchedule(
        selectedBuyer.getId(), request.candidateTimeId(), candidateTime.getStartsAt(), confirmedAt);

    statusHistoryRepository.save(
        CoordinationStatusHistory.staffTransition(
            coordinationId, previousStatus, coordination.getStatus(), membershipId));

    return new CoordinationConfirmResponse(
        coordination.getStatus(), coordination.getScheduledAt(), selectedBuyer.getId());
  }
}
