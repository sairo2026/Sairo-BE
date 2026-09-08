package com.sairo.be.domain.coordination.service;

import com.sairo.be.domain.coordination.dto.request.CoordinationCreateRequest;
import com.sairo.be.domain.coordination.dto.response.CoordinationCreateResponse;
import com.sairo.be.domain.coordination.entity.Coordination;
import com.sairo.be.domain.coordination.entity.CoordinationCandidateTime;
import com.sairo.be.domain.coordination.entity.CoordinationCustomerResponse;
import com.sairo.be.domain.coordination.entity.CoordinationStatusHistory;
import com.sairo.be.domain.coordination.entity.CustomerResponseCandidate;
import com.sairo.be.domain.coordination.mapper.CoordinationMapper;
import com.sairo.be.domain.coordination.repository.CoordinationCandidateTimeRepository;
import com.sairo.be.domain.coordination.repository.CoordinationCustomerResponseRepository;
import com.sairo.be.domain.coordination.repository.CoordinationRepository;
import com.sairo.be.domain.coordination.repository.CoordinationStatusHistoryRepository;
import com.sairo.be.domain.coordination.repository.CustomerResponseCandidateRepository;
import com.sairo.be.domain.property.service.PropertyService;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class CoordinationService {

  private final PropertyService propertyService;
  private final CoordinationRepository coordinationRepository;
  private final CoordinationCandidateTimeRepository candidateTimeRepository;
  private final CoordinationCustomerResponseRepository customerResponseRepository;
  private final CustomerResponseCandidateRepository responseCandidateRepository;
  private final CoordinationStatusHistoryRepository statusHistoryRepository;
  private final PublicLinkIssuer linkIssuer;

  public CoordinationService(
      PropertyService propertyService,
      CoordinationRepository coordinationRepository,
      CoordinationCandidateTimeRepository candidateTimeRepository,
      CoordinationCustomerResponseRepository customerResponseRepository,
      CustomerResponseCandidateRepository responseCandidateRepository,
      CoordinationStatusHistoryRepository statusHistoryRepository,
      PublicLinkIssuer linkIssuer) {
    this.propertyService = propertyService;
    this.coordinationRepository = coordinationRepository;
    this.candidateTimeRepository = candidateTimeRepository;
    this.customerResponseRepository = customerResponseRepository;
    this.responseCandidateRepository = responseCandidateRepository;
    this.statusHistoryRepository = statusHistoryRepository;
    this.linkIssuer = linkIssuer;
  }

  @Transactional
  public CoordinationCreateResponse createForTenant(
      Long officeId, Long membershipId, Long propertyId, CoordinationCreateRequest request) {
    propertyService.getProperty(officeId, propertyId);
    validateNoDuplicateCandidateTimes(request.candidateTimes());

    Coordination coordination = Coordination.startForTenant(officeId, propertyId, membershipId);
    coordinationRepository.save(coordination);

    List<CoordinationCandidateTime> candidateTimes =
        request.candidateTimes().stream()
            .map(item -> CoordinationCandidateTime.of(coordination.getId(), item.startsAt()))
            .toList();
    candidateTimeRepository.saveAll(candidateTimes);

    CoordinationCustomerResponse tenantResponse =
        CoordinationCustomerResponse.waitingForTenant(
            coordination.getId(), request.tenantName(), request.tenantPhone());
    customerResponseRepository.save(tenantResponse);

    responseCandidateRepository.saveAll(
        candidateTimes.stream()
            .map(
                candidate ->
                    CustomerResponseCandidate.offered(
                        tenantResponse.getId(), coordination.getId(), candidate.getId()))
            .toList());

    PublicLinkIssuer.IssuedLink issuedLink = linkIssuer.issue(tenantResponse.getId());

    statusHistoryRepository.save(
        CoordinationStatusHistory.initialTransition(
            coordination.getId(), coordination.getStatus(), membershipId));

    return CoordinationMapper.toCreateResponse(
        coordination, tenantResponse, issuedLink.link(), issuedLink.customerLinkUrl());
  }

  private void validateNoDuplicateCandidateTimes(
      List<CoordinationCreateRequest.CandidateTime> candidateTimes) {
    Set<Instant> distinctStartTimes =
        candidateTimes.stream()
            .map(CoordinationCreateRequest.CandidateTime::startsAt)
            .collect(Collectors.toSet());
    if (distinctStartTimes.size() != candidateTimes.size()) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
  }
}
