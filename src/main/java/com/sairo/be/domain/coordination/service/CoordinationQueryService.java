package com.sairo.be.domain.coordination.service;

import com.sairo.be.domain.coordination.dto.response.CoordinationDetailResponse;
import com.sairo.be.domain.coordination.dto.response.CoordinationListResponse;
import com.sairo.be.domain.coordination.entity.Coordination;
import com.sairo.be.domain.coordination.entity.CoordinationCandidateTime;
import com.sairo.be.domain.coordination.entity.CoordinationCustomerResponse;
import com.sairo.be.domain.coordination.entity.CoordinationStatus;
import com.sairo.be.domain.coordination.entity.CustomerResponseCandidate;
import com.sairo.be.domain.coordination.entity.CustomerResponseLink;
import com.sairo.be.domain.coordination.entity.CustomerResponseResult;
import com.sairo.be.domain.coordination.entity.CustomerResponseRole;
import com.sairo.be.domain.coordination.mapper.CoordinationMapper;
import com.sairo.be.domain.coordination.repository.CoordinationCandidateTimeRepository;
import com.sairo.be.domain.coordination.repository.CoordinationCustomerResponseRepository;
import com.sairo.be.domain.coordination.repository.CoordinationRepository;
import com.sairo.be.domain.coordination.repository.CustomerResponseCandidateRepository;
import com.sairo.be.domain.coordination.repository.CustomerResponseLinkRepository;
import com.sairo.be.domain.property.dto.response.PropertyDetailResponse;
import com.sairo.be.domain.property.dto.response.PropertyListResponse;
import com.sairo.be.domain.property.service.PropertyService;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class CoordinationQueryService {

  private final CoordinationRepository coordinationRepository;
  private final CoordinationCustomerResponseRepository responseRepository;
  private final CoordinationCandidateTimeRepository candidateTimeRepository;
  private final CustomerResponseCandidateRepository responseCandidateRepository;
  private final CustomerResponseLinkRepository linkRepository;
  private final PropertyService propertyService;
  private final PublicLinkIssuer linkIssuer;

  public CoordinationQueryService(
      CoordinationRepository coordinationRepository,
      CoordinationCustomerResponseRepository responseRepository,
      CoordinationCandidateTimeRepository candidateTimeRepository,
      CustomerResponseCandidateRepository responseCandidateRepository,
      CustomerResponseLinkRepository linkRepository,
      PropertyService propertyService,
      PublicLinkIssuer linkIssuer) {
    this.coordinationRepository = coordinationRepository;
    this.responseRepository = responseRepository;
    this.candidateTimeRepository = candidateTimeRepository;
    this.responseCandidateRepository = responseCandidateRepository;
    this.linkRepository = linkRepository;
    this.propertyService = propertyService;
    this.linkIssuer = linkIssuer;
  }

  @Transactional(readOnly = true)
  public CoordinationListResponse list(Long officeId) {
    List<Coordination> coordinations =
        coordinationRepository.findByOfficeIdOrderByCreatedAtDesc(officeId);
    Map<Long, PropertyListResponse.PropertyItem> propertiesById =
        propertyService.listProperties(officeId).properties().stream()
            .collect(
                Collectors.toMap(
                    PropertyListResponse.PropertyItem::propertyId, Function.identity()));
    List<Long> coordinationIds = coordinations.stream().map(Coordination::getId).toList();
    List<CoordinationCustomerResponse> responses =
        coordinationIds.isEmpty()
            ? List.of()
            : responseRepository.findByCoordinationIdInOrderByIdAsc(coordinationIds);
    Map<Long, CoordinationCustomerResponse> tenantsByCoordinationId =
        responses.stream()
            .filter(response -> response.getRole() == CustomerResponseRole.TENANT)
            .collect(
                Collectors.toMap(
                    CoordinationCustomerResponse::getCoordinationId, Function.identity()));
    Map<Long, List<CustomerResponseResult>> buyerResultsByCoordinationId =
        responses.stream()
            .filter(response -> response.getRole() == CustomerResponseRole.BUYER)
            .collect(
                Collectors.groupingBy(
                    CoordinationCustomerResponse::getCoordinationId,
                    Collectors.mapping(
                        CoordinationCustomerResponse::getResult, Collectors.toList())));

    List<CoordinationListResponse.CoordinationItem> items =
        coordinations.stream()
            .map(
                coordination ->
                    CoordinationMapper.toListItem(
                        coordination,
                        requiredProperty(propertiesById, coordination.getPropertyId()),
                        requiredTenant(tenantsByCoordinationId, coordination.getId()),
                        buyerResultsByCoordinationId.getOrDefault(coordination.getId(), List.of())))
            .toList();
    return new CoordinationListResponse(statusCounts(officeId), items);
  }

  @Transactional(readOnly = true)
  public CoordinationDetailResponse getDetail(Long officeId, Long coordinationId) {
    Coordination coordination =
        coordinationRepository
            .findByIdAndOfficeId(coordinationId, officeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    PropertyDetailResponse property =
        propertyService.getProperty(officeId, coordination.getPropertyId());
    List<CoordinationCandidateTime> candidateTimes =
        candidateTimeRepository.findByCoordinationIdOrderByStartsAtAsc(coordinationId);
    List<CoordinationCustomerResponse> responses =
        responseRepository.findByCoordinationIdOrderByIdAsc(coordinationId);
    List<Long> responseIds = responses.stream().map(CoordinationCustomerResponse::getId).toList();
    Map<Long, List<CustomerResponseCandidate>> candidatesByResponseId =
        responseIds.isEmpty()
            ? Collections.emptyMap()
            : responseCandidateRepository.findById_ResponseIdIn(responseIds).stream()
                .collect(Collectors.groupingBy(candidate -> candidate.getId().getResponseId()));
    Map<Long, CustomerResponseLink> linksByResponseId =
        responseIds.isEmpty()
            ? Collections.emptyMap()
            : linkRepository.findByResponseIdInAndRevokedAtIsNull(responseIds).stream()
                .collect(
                    Collectors.toMap(CustomerResponseLink::getResponseId, Function.identity()));
    Instant now = Instant.now();

    List<CoordinationDetailResponse.CustomerResponseItem> responseItems =
        responses.stream()
            .map(
                response ->
                    toResponseItem(
                        response,
                        candidateTimes,
                        candidatesByResponseId.getOrDefault(response.getId(), List.of()),
                        linksByResponseId.get(response.getId()),
                        now))
            .toList();
    CoordinationDetailResponse.CustomerResponseItem tenantResponse =
        responseItems.stream()
            .filter(item -> item.role() == CustomerResponseRole.TENANT)
            .findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    List<CoordinationDetailResponse.CustomerResponseItem> buyerResponses =
        responseItems.stream().filter(item -> item.role() == CustomerResponseRole.BUYER).toList();

    return CoordinationMapper.toDetailResponse(
        coordination, property, candidateTimes, tenantResponse, buyerResponses);
  }

  private CoordinationDetailResponse.CustomerResponseItem toResponseItem(
      CoordinationCustomerResponse response,
      List<CoordinationCandidateTime> coordinationCandidates,
      List<CustomerResponseCandidate> responseCandidates,
      CustomerResponseLink link,
      Instant now) {
    List<Long> offeredCandidateIds =
        response.getRole() == CustomerResponseRole.TENANT && response.getResetCount() == 0
            ? coordinationCandidates.stream().map(CoordinationCandidateTime::getId).toList()
            : responseCandidates.stream()
                .map(candidate -> candidate.getId().getCandidateTimeId())
                .sorted()
                .toList();
    List<Long> selectedCandidateIds =
        responseCandidates.stream()
            .filter(CustomerResponseCandidate::isSelected)
            .map(candidate -> candidate.getId().getCandidateTimeId())
            .sorted()
            .toList();
    CustomerResponseResult effectiveResult = effectiveResult(response, link, now);
    String linkUrl = link == null ? null : linkIssuer.reconstructUrl(link);
    Instant linkExpiresAt = link == null ? null : link.getExpiresAt();
    return CoordinationMapper.toCustomerResponseItem(
        response,
        effectiveResult,
        offeredCandidateIds,
        selectedCandidateIds,
        linkUrl,
        linkExpiresAt);
  }

  private CustomerResponseResult effectiveResult(
      CoordinationCustomerResponse response, CustomerResponseLink link, Instant now) {
    if (response.getResult() == CustomerResponseResult.WAITING
        && link != null
        && !link.isActive(now)) {
      return CustomerResponseResult.EXPIRED;
    }
    return response.getResult();
  }

  private PropertyListResponse.PropertyItem requiredProperty(
      Map<Long, PropertyListResponse.PropertyItem> propertiesById, Long propertyId) {
    PropertyListResponse.PropertyItem property = propertiesById.get(propertyId);
    if (property == null) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    return property;
  }

  private CoordinationCustomerResponse requiredTenant(
      Map<Long, CoordinationCustomerResponse> tenantsByCoordinationId, Long coordinationId) {
    CoordinationCustomerResponse tenant = tenantsByCoordinationId.get(coordinationId);
    if (tenant == null) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    return tenant;
  }

  private CoordinationListResponse.StatusCounts statusCounts(Long officeId) {
    return new CoordinationListResponse.StatusCounts(
        count(officeId, CoordinationStatus.TENANT_CHECKING),
        count(officeId, CoordinationStatus.BUYER_DELIVERY_REQUIRED),
        count(officeId, CoordinationStatus.BUYER_CHECKING),
        count(officeId, CoordinationStatus.FINAL_CONFIRMATION_REQUIRED),
        count(officeId, CoordinationStatus.SCHEDULE_CONFIRMED),
        count(officeId, CoordinationStatus.VISIT_COMPLETED));
  }

  private long count(Long officeId, CoordinationStatus status) {
    return coordinationRepository.countByOfficeIdAndStatus(officeId, status);
  }
}
