package com.sairo.be.domain.coordination.service;

import com.sairo.be.domain.coordination.dto.response.BuyerCreateResponse;
import com.sairo.be.domain.coordination.entity.Coordination;
import com.sairo.be.domain.coordination.entity.CoordinationCustomerResponse;
import com.sairo.be.domain.coordination.entity.CoordinationStatus;
import com.sairo.be.domain.coordination.entity.CoordinationStatusHistory;
import com.sairo.be.domain.coordination.entity.CustomerResponseCandidate;
import com.sairo.be.domain.coordination.entity.CustomerResponseRole;
import com.sairo.be.domain.coordination.repository.CoordinationCustomerResponseRepository;
import com.sairo.be.domain.coordination.repository.CoordinationRepository;
import com.sairo.be.domain.coordination.repository.CoordinationStatusHistoryRepository;
import com.sairo.be.domain.coordination.repository.CustomerResponseCandidateRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class BuyerCoordinationService {

  private final CoordinationRepository coordinationRepository;
  private final CoordinationCustomerResponseRepository responseRepository;
  private final CustomerResponseCandidateRepository responseCandidateRepository;
  private final CoordinationStatusHistoryRepository statusHistoryRepository;
  private final PublicLinkIssuer linkIssuer;

  public BuyerCoordinationService(
      CoordinationRepository coordinationRepository,
      CoordinationCustomerResponseRepository responseRepository,
      CustomerResponseCandidateRepository responseCandidateRepository,
      CoordinationStatusHistoryRepository statusHistoryRepository,
      PublicLinkIssuer linkIssuer) {
    this.coordinationRepository = coordinationRepository;
    this.responseRepository = responseRepository;
    this.responseCandidateRepository = responseCandidateRepository;
    this.statusHistoryRepository = statusHistoryRepository;
    this.linkIssuer = linkIssuer;
  }

  @Transactional
  public BuyerCreateResponse addBuyer(Long officeId, Long membershipId, Long coordinationId) {
    Coordination coordination =
        coordinationRepository
            .findByIdAndOfficeIdForUpdate(coordinationId, officeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

    if (coordination.getStatus() == CoordinationStatus.TENANT_CHECKING) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }

    CoordinationCustomerResponse tenantResponse =
        responseRepository
            .findByCoordinationIdAndRole(coordinationId, CustomerResponseRole.TENANT)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

    boolean isFirstBuyer =
        !responseRepository.existsByCoordinationIdAndRole(
            coordinationId, CustomerResponseRole.BUYER);

    CoordinationCustomerResponse buyerResponse =
        CoordinationCustomerResponse.waitingForBuyer(coordinationId);
    responseRepository.save(buyerResponse);

    responseCandidateRepository.saveAll(
        responseCandidateRepository.findById_ResponseId(tenantResponse.getId()).stream()
            .filter(CustomerResponseCandidate::isSelected)
            .map(
                candidate ->
                    CustomerResponseCandidate.offered(
                        buyerResponse.getId(),
                        coordinationId,
                        candidate.getId().getCandidateTimeId()))
            .toList());

    PublicLinkIssuer.IssuedLink issuedLink = linkIssuer.issue(buyerResponse.getId());

    if (isFirstBuyer && coordination.getStatus() == CoordinationStatus.BUYER_DELIVERY_REQUIRED) {
      CoordinationStatus previousStatus = coordination.getStatus();
      coordination.startBuyerChecking();
      statusHistoryRepository.save(
          CoordinationStatusHistory.staffTransition(
              coordinationId, previousStatus, coordination.getStatus(), membershipId));
    }

    return new BuyerCreateResponse(
        buyerResponse.getId(),
        issuedLink.customerLinkUrl(),
        issuedLink.link().getExpiresAt(),
        coordination.getStatus());
  }
}
