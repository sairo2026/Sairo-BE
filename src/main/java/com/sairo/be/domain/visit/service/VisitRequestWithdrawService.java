package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.visit.dto.response.VisitRequestResponse;
import com.sairo.be.domain.visit.entity.RequestStatus;
import com.sairo.be.domain.visit.entity.RequestStatusSource;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.entity.VisitRequest;
import com.sairo.be.domain.visit.mapper.VisitRequestMapper;
import com.sairo.be.domain.visit.repository.VisitRequestRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import jakarta.persistence.EntityManager;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class VisitRequestWithdrawService {

  private static final Set<String> KEYS = Set.of("reason");
  private static final int REASON_MAX = 500;

  private final CoordinationAccess access;
  private final VisitRequestRepository requestRepository;
  private final VisitRequestStatusService statusService;
  private final EntityManager entityManager;

  public VisitRequestWithdrawService(
      CoordinationAccess access,
      VisitRequestRepository requestRepository,
      VisitRequestStatusService statusService,
      EntityManager entityManager) {
    this.access = access;
    this.requestRepository = requestRepository;
    this.statusService = statusService;
    this.entityManager = entityManager;
  }

  @Transactional
  public VisitRequestResponse withdraw(
      StaffPrincipal principal, Long requestId, Long expectedVersion, JsonNode body) {
    String reason = null;
    if (body != null && !body.isNull()) {
      CustomerInput.requireObject(body, KEYS);
      reason = CustomerInput.optionalText(body, "reason", REASON_MAX, false);
    }
    VisitRequest preview =
        requestRepository
            .findByIdAndApplicantOfficeId(requestId, principal.officeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    Long coordinationId = preview.getCoordinationId();
    entityManager.clear();
    VisitCoordination coordination = access.lock(principal, coordinationId);
    VisitRequest request =
        requestRepository
            .findForUpdate(requestId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (expectedVersion != null && !expectedVersion.equals(request.getVersion())) {
      throw BusinessException.versionConflict(request.getVersion());
    }
    if (!request.getStatus().isActive()) {
      throw new BusinessException(ErrorCode.VISIT_REQUEST_INVALID_STATE);
    }
    statusService.close(
        request,
        coordination,
        RequestStatus.WITHDRAWN,
        RequestStatusSource.BROKER,
        principal.membershipId(),
        reason);
    return VisitRequestMapper.toResponse(request);
  }
}
