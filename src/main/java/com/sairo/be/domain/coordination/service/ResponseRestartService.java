package com.sairo.be.domain.coordination.service;

import com.sairo.be.domain.coordination.dto.request.ResponseRestartRequest;
import com.sairo.be.domain.coordination.dto.response.ResponseRestartResponse;
import com.sairo.be.domain.coordination.entity.Coordination;
import com.sairo.be.domain.coordination.entity.CoordinationCandidateTime;
import com.sairo.be.domain.coordination.entity.CoordinationCustomerResponse;
import com.sairo.be.domain.coordination.entity.CustomerResponseCandidate;
import com.sairo.be.domain.coordination.entity.CustomerResponseLink;
import com.sairo.be.domain.coordination.entity.CustomerResponseResult;
import com.sairo.be.domain.coordination.entity.CustomerResponseRole;
import com.sairo.be.domain.coordination.repository.CoordinationCandidateTimeRepository;
import com.sairo.be.domain.coordination.repository.CoordinationCustomerResponseRepository;
import com.sairo.be.domain.coordination.repository.CoordinationRepository;
import com.sairo.be.domain.coordination.repository.CustomerResponseCandidateRepository;
import com.sairo.be.domain.coordination.repository.CustomerResponseLinkRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class ResponseRestartService {

  private final CoordinationRepository coordinationRepository;
  private final CoordinationCustomerResponseRepository responseRepository;
  private final CoordinationCandidateTimeRepository candidateTimeRepository;
  private final CustomerResponseCandidateRepository responseCandidateRepository;
  private final CustomerResponseLinkRepository linkRepository;
  private final PublicLinkIssuer linkIssuer;

  public ResponseRestartService(
      CoordinationRepository coordinationRepository,
      CoordinationCustomerResponseRepository responseRepository,
      CoordinationCandidateTimeRepository candidateTimeRepository,
      CustomerResponseCandidateRepository responseCandidateRepository,
      CustomerResponseLinkRepository linkRepository,
      PublicLinkIssuer linkIssuer) {
    this.coordinationRepository = coordinationRepository;
    this.responseRepository = responseRepository;
    this.candidateTimeRepository = candidateTimeRepository;
    this.responseCandidateRepository = responseCandidateRepository;
    this.linkRepository = linkRepository;
    this.linkIssuer = linkIssuer;
  }

  @Transactional
  public ResponseRestartResponse restart(
      Long officeId, Long coordinationId, Long responseId, ResponseRestartRequest request) {
    Coordination coordination = getLockedCoordination(officeId, coordinationId);
    CoordinationCustomerResponse response = getLockedResponse(coordinationId, responseId);
    CustomerResponseLink currentLink =
        linkRepository.findByResponseIdAndRevokedAtIsNull(responseId).orElse(null);
    Instant now = Instant.now();
    validateRestartable(response, currentLink, now);

    Set<Long> requestedCandidateIds = new HashSet<>(request.candidateTimeIds());
    if (requestedCandidateIds.size() != request.candidateTimeIds().size()) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
    Set<Long> allowedCandidateIds = resolveAllowedCandidateIds(coordination, response);
    if (!allowedCandidateIds.containsAll(requestedCandidateIds)) {
      throw new BusinessException(ErrorCode.CANDIDATE_NOT_ALLOWED);
    }

    if (currentLink != null) {
      currentLink.revoke(now);
      linkRepository.saveAndFlush(currentLink);
    }
    responseCandidateRepository.deleteByResponseId(responseId);
    responseCandidateRepository.saveAll(
        request.candidateTimeIds().stream()
            .map(
                candidateTimeId ->
                    CustomerResponseCandidate.offered(responseId, coordinationId, candidateTimeId))
            .toList());
    response.restart();

    PublicLinkIssuer.IssuedLink issuedLink = linkIssuer.issue(responseId);
    return new ResponseRestartResponse(
        issuedLink.customerLinkUrl(), issuedLink.link().getExpiresAt());
  }

  private Coordination getLockedCoordination(Long officeId, Long coordinationId) {
    return coordinationRepository
        .findByIdAndOfficeIdForUpdate(coordinationId, officeId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  private CoordinationCustomerResponse getLockedResponse(Long coordinationId, Long responseId) {
    CoordinationCustomerResponse response =
        responseRepository
            .findByIdForUpdate(responseId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!response.getCoordinationId().equals(coordinationId)) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    return response;
  }

  private void validateRestartable(
      CoordinationCustomerResponse response, CustomerResponseLink currentLink, Instant now) {
    boolean expiredWaiting =
        response.getResult() == CustomerResponseResult.WAITING
            && currentLink != null
            && !currentLink.isActive(now);
    boolean restartable =
        response.getResult() == CustomerResponseResult.NONE_AVAILABLE
            || response.getResult() == CustomerResponseResult.EXPIRED
            || expiredWaiting;
    if (!restartable) {
      throw new BusinessException(ErrorCode.RESPONSE_NOT_RESTARTABLE);
    }
  }

  private Set<Long> resolveAllowedCandidateIds(
      Coordination coordination, CoordinationCustomerResponse response) {
    if (response.getRole() == CustomerResponseRole.TENANT) {
      return candidateTimeRepository
          .findByCoordinationIdOrderByStartsAtAsc(coordination.getId())
          .stream()
          .map(CoordinationCandidateTime::getId)
          .collect(Collectors.toSet());
    }

    CoordinationCustomerResponse tenantResponse =
        responseRepository
            .findByCoordinationIdAndRole(coordination.getId(), CustomerResponseRole.TENANT)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    return responseCandidateRepository.findById_ResponseId(tenantResponse.getId()).stream()
        .filter(CustomerResponseCandidate::isSelected)
        .map(candidate -> candidate.getId().getCandidateTimeId())
        .collect(Collectors.toSet());
  }
}
