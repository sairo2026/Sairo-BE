package com.sairo.be.domain.coordination.service;

import com.sairo.be.domain.coordination.dto.request.CoordinationCreateRequest;
import com.sairo.be.domain.coordination.dto.response.CoordinationCreateResponse;
import com.sairo.be.domain.coordination.entity.Coordination;
import com.sairo.be.domain.coordination.entity.CoordinationCandidateTime;
import com.sairo.be.domain.coordination.entity.CoordinationCustomerResponse;
import com.sairo.be.domain.coordination.entity.CoordinationStatusHistory;
import com.sairo.be.domain.coordination.entity.CustomerResponseLink;
import com.sairo.be.domain.coordination.mapper.CoordinationMapper;
import com.sairo.be.domain.coordination.repository.CoordinationCandidateTimeRepository;
import com.sairo.be.domain.coordination.repository.CoordinationCustomerResponseRepository;
import com.sairo.be.domain.coordination.repository.CoordinationRepository;
import com.sairo.be.domain.coordination.repository.CoordinationStatusHistoryRepository;
import com.sairo.be.domain.coordination.repository.CustomerResponseLinkRepository;
import com.sairo.be.domain.property.service.PropertyService;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
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
  private final CustomerResponseLinkRepository customerResponseLinkRepository;
  private final CoordinationStatusHistoryRepository statusHistoryRepository;
  private final PublicLinkTokenGenerator tokenGenerator;
  private final String frontendBaseUrl;

  public CoordinationService(
      PropertyService propertyService,
      CoordinationRepository coordinationRepository,
      CoordinationCandidateTimeRepository candidateTimeRepository,
      CoordinationCustomerResponseRepository customerResponseRepository,
      CustomerResponseLinkRepository customerResponseLinkRepository,
      CoordinationStatusHistoryRepository statusHistoryRepository,
      PublicLinkTokenGenerator tokenGenerator,
      @Value("${app.frontend-base-url:http://localhost:3000}") String frontendBaseUrl) {
    this.propertyService = propertyService;
    this.coordinationRepository = coordinationRepository;
    this.candidateTimeRepository = candidateTimeRepository;
    this.customerResponseRepository = customerResponseRepository;
    this.customerResponseLinkRepository = customerResponseLinkRepository;
    this.statusHistoryRepository = statusHistoryRepository;
    this.tokenGenerator = tokenGenerator;
    this.frontendBaseUrl = frontendBaseUrl;
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

    IssuedLink issuedLink = issueLink(tenantResponse.getId());

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

  private IssuedLink issueLink(Long responseId) {
    Instant issuedAt = Instant.now();
    String provisionalHash = tokenGenerator.hash(UUID.randomUUID().toString());
    CustomerResponseLink link =
        CustomerResponseLink.issueWithProvisionalHash(responseId, provisionalHash, issuedAt);
    customerResponseLinkRepository.save(link);

    String token = tokenGenerator.generate(link.getId(), link.getIssuedAt());
    link.assignTokenHash(tokenGenerator.hash(token));

    return new IssuedLink(link, frontendBaseUrl + "/visit-responses/" + token);
  }

  private record IssuedLink(CustomerResponseLink link, String customerLinkUrl) {}
}
