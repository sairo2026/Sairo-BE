package com.sairo.be.domain.coordination.service;

import com.sairo.be.domain.coordination.dto.request.AvailableTimesSubmitRequest;
import com.sairo.be.domain.coordination.dto.response.PublicVisitResponse;
import com.sairo.be.domain.coordination.entity.Coordination;
import com.sairo.be.domain.coordination.entity.CoordinationCandidateTime;
import com.sairo.be.domain.coordination.entity.CoordinationCustomerResponse;
import com.sairo.be.domain.coordination.entity.CoordinationStatus;
import com.sairo.be.domain.coordination.entity.CoordinationStatusHistory;
import com.sairo.be.domain.coordination.entity.CoordinationStatusSource;
import com.sairo.be.domain.coordination.entity.CustomerResponseCandidate;
import com.sairo.be.domain.coordination.entity.CustomerResponseLink;
import com.sairo.be.domain.coordination.entity.CustomerResponseResult;
import com.sairo.be.domain.coordination.entity.CustomerResponseRole;
import com.sairo.be.domain.coordination.mapper.CoordinationMapper;
import com.sairo.be.domain.coordination.repository.CoordinationCandidateTimeRepository;
import com.sairo.be.domain.coordination.repository.CoordinationCustomerResponseRepository;
import com.sairo.be.domain.coordination.repository.CoordinationRepository;
import com.sairo.be.domain.coordination.repository.CoordinationStatusHistoryRepository;
import com.sairo.be.domain.coordination.repository.CustomerResponseCandidateRepository;
import com.sairo.be.domain.coordination.repository.CustomerResponseLinkRepository;
import com.sairo.be.domain.office.service.OfficeQueryService;
import com.sairo.be.domain.property.dto.response.PropertyDetailResponse;
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
public class PublicVisitResponseService {

  private final PublicLinkTokenGenerator tokenGenerator;
  private final CustomerResponseLinkRepository linkRepository;
  private final CoordinationCustomerResponseRepository responseRepository;
  private final CoordinationCandidateTimeRepository candidateTimeRepository;
  private final CustomerResponseCandidateRepository responseCandidateRepository;
  private final CoordinationRepository coordinationRepository;
  private final CoordinationStatusHistoryRepository statusHistoryRepository;
  private final PropertyService propertyService;
  private final OfficeQueryService officeQueryService;

  public PublicVisitResponseService(
      PublicLinkTokenGenerator tokenGenerator,
      CustomerResponseLinkRepository linkRepository,
      CoordinationCustomerResponseRepository responseRepository,
      CoordinationCandidateTimeRepository candidateTimeRepository,
      CustomerResponseCandidateRepository responseCandidateRepository,
      CoordinationRepository coordinationRepository,
      CoordinationStatusHistoryRepository statusHistoryRepository,
      PropertyService propertyService,
      OfficeQueryService officeQueryService) {
    this.tokenGenerator = tokenGenerator;
    this.linkRepository = linkRepository;
    this.responseRepository = responseRepository;
    this.candidateTimeRepository = candidateTimeRepository;
    this.responseCandidateRepository = responseCandidateRepository;
    this.coordinationRepository = coordinationRepository;
    this.statusHistoryRepository = statusHistoryRepository;
    this.propertyService = propertyService;
    this.officeQueryService = officeQueryService;
  }

  @Transactional(readOnly = true)
  public PublicVisitResponse getByToken(String token) {
    CustomerResponseLink link = resolveActiveLink(token);
    CoordinationCustomerResponse response = getResponse(link.getResponseId());
    Coordination coordination = getCoordination(response.getCoordinationId());
    return buildResponse(link, response, coordination);
  }

  @Transactional
  public PublicVisitResponse submitAvailableTimes(
      String token, AvailableTimesSubmitRequest request) {
    CustomerResponseLink link = resolveActiveLink(token);
    LockedSubmission lockedSubmission = lockSubmission(link.getResponseId());
    CoordinationCustomerResponse response = lockedSubmission.response();
    if (!response.isAvailableTimesSubmittable()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }

    Set<Long> offeredIds = resolveOfferedCandidateIds(response);
    Set<Long> submittedIds = Set.copyOf(request.candidateTimeIds());
    if (!offeredIds.containsAll(submittedIds)) {
      throw new BusinessException(ErrorCode.CANDIDATE_NOT_ALLOWED);
    }

    boolean firstSubmission = response.isWaiting();
    Coordination coordination = lockedSubmission.coordination();

    response.submitAvailability(Instant.now());
    applySelection(response.getId(), coordination.getId(), submittedIds);

    if (firstSubmission) {
      advanceOnFirstSubmission(coordination, response.getRole());
    }

    return buildResponse(link, response, coordination);
  }

  @Transactional
  public PublicVisitResponse submitNoAvailability(String token) {
    CustomerResponseLink link = resolveActiveLink(token);
    LockedSubmission lockedSubmission = lockSubmission(link.getResponseId());
    CoordinationCustomerResponse response = lockedSubmission.response();
    if (!response.isWaiting()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    response.submitNoAvailability(Instant.now());
    return buildResponse(link, response, lockedSubmission.coordination());
  }

  private void advanceOnFirstSubmission(Coordination coordination, CustomerResponseRole role) {
    if (role == CustomerResponseRole.TENANT) {
      if (coordination.getStatus() != CoordinationStatus.TENANT_CHECKING) {
        throw new BusinessException(ErrorCode.INVALID_TRANSITION);
      }
      coordination.receiveTenantAvailability();
      statusHistoryRepository.save(
          CoordinationStatusHistory.customerTransition(
              coordination.getId(),
              CoordinationStatus.TENANT_CHECKING,
              CoordinationStatus.BUYER_DELIVERY_REQUIRED,
              CoordinationStatusSource.TENANT));
      return;
    }

    if (coordination.getStatus() != CoordinationStatus.BUYER_CHECKING) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    coordination.receiveFirstBuyerAvailability();
    statusHistoryRepository.save(
        CoordinationStatusHistory.customerTransition(
            coordination.getId(),
            CoordinationStatus.BUYER_CHECKING,
            CoordinationStatus.FINAL_CONFIRMATION_REQUIRED,
            CoordinationStatusSource.BUYER));
  }

  private void applySelection(Long responseId, Long coordinationId, Set<Long> submittedIds) {
    List<CustomerResponseCandidate> existing =
        responseCandidateRepository.findById_ResponseId(responseId);
    Set<Long> existingIds =
        existing.stream()
            .map(candidate -> candidate.getId().getCandidateTimeId())
            .collect(Collectors.toSet());

    for (CustomerResponseCandidate candidate : existing) {
      if (submittedIds.contains(candidate.getId().getCandidateTimeId())) {
        candidate.markSelected();
      } else {
        candidate.markUnselected();
      }
    }
    responseCandidateRepository.saveAll(existing);

    for (Long candidateTimeId : submittedIds) {
      if (!existingIds.contains(candidateTimeId)) {
        responseCandidateRepository.save(
            CustomerResponseCandidate.selected(responseId, coordinationId, candidateTimeId));
      }
    }
  }

  private Set<Long> resolveOfferedCandidateIds(CoordinationCustomerResponse response) {
    if (response.getRole() == CustomerResponseRole.TENANT) {
      return candidateTimeRepository
          .findByCoordinationIdOrderByStartsAtAsc(response.getCoordinationId())
          .stream()
          .map(CoordinationCandidateTime::getId)
          .collect(Collectors.toSet());
    }
    return responseCandidateRepository.findById_ResponseId(response.getId()).stream()
        .map(candidate -> candidate.getId().getCandidateTimeId())
        .collect(Collectors.toSet());
  }

  private List<CoordinationCandidateTime> resolveOfferedCandidates(
      CoordinationCustomerResponse response) {
    if (response.getRole() == CustomerResponseRole.TENANT) {
      return candidateTimeRepository.findByCoordinationIdOrderByStartsAtAsc(
          response.getCoordinationId());
    }
    return candidateTimeRepository.findAllById(resolveOfferedCandidateIds(response));
  }

  private PublicVisitResponse buildResponse(
      CustomerResponseLink link, CoordinationCustomerResponse response, Coordination coordination) {
    PropertyDetailResponse property =
        propertyService.getProperty(coordination.getOfficeId(), coordination.getPropertyId());
    String officeName = officeQueryService.getOfficeName(coordination.getOfficeId());
    List<CoordinationCandidateTime> offeredCandidates = resolveOfferedCandidates(response);
    List<Long> selectedCandidateIds =
        responseCandidateRepository.findById_ResponseId(response.getId()).stream()
            .filter(CustomerResponseCandidate::isSelected)
            .map(candidate -> candidate.getId().getCandidateTimeId())
            .toList();
    Instant scheduledAt = resolveScheduledAt(response, coordination);
    return CoordinationMapper.toPublicVisitResponse(
        officeName,
        property,
        response,
        offeredCandidates,
        selectedCandidateIds,
        scheduledAt,
        link.getExpiresAt());
  }

  private Instant resolveScheduledAt(
      CoordinationCustomerResponse response, Coordination coordination) {
    if (coordination.getScheduledAt() == null) {
      return null;
    }
    boolean visible =
        response.getRole() == CustomerResponseRole.TENANT
            || response.getResult() == CustomerResponseResult.CONFIRMED;
    return visible ? coordination.getScheduledAt() : null;
  }

  private CustomerResponseLink resolveActiveLink(String token) {
    String tokenHash = tokenGenerator.hash(token);
    CustomerResponseLink link =
        linkRepository
            .findByTokenHash(tokenHash)
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    if (!link.isActive(Instant.now())) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    }
    return link;
  }

  private CoordinationCustomerResponse getResponse(Long responseId) {
    return responseRepository
        .findById(responseId)
        .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
  }

  private Coordination getCoordination(Long coordinationId) {
    return coordinationRepository
        .findById(coordinationId)
        .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
  }

  private LockedSubmission lockSubmission(Long responseId) {
    Long coordinationId =
        responseRepository
            .findCoordinationIdById(responseId)
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    Coordination coordination =
        coordinationRepository
            .findByIdForUpdate(coordinationId)
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    CoordinationCustomerResponse lockedResponse =
        responseRepository
            .findByIdForUpdate(responseId)
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    return new LockedSubmission(coordination, lockedResponse);
  }

  private record LockedSubmission(
      Coordination coordination, CoordinationCustomerResponse response) {}
}
