package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.property.service.PropertyReferenceService.PropertySummary;
import com.sairo.be.domain.visit.dto.response.CandidatePropertyAddResponse;
import com.sairo.be.domain.visit.entity.VisitCandidateProperty;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.repository.VisitCandidatePropertyRepository;
import com.sairo.be.domain.visit.repository.VisitCoordinationRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class CandidatePropertyService {

  private static final Set<String> ADD_KEYS = Set.of("propertyId", "sortOrder");

  private final CoordinationAccess access;
  private final VisitCoordinationRepository coordinationRepository;
  private final VisitCandidatePropertyRepository candidateRepository;
  private final CandidateAdmission candidateAdmission;
  private final Clock clock;

  public CandidatePropertyService(
      CoordinationAccess access,
      VisitCoordinationRepository coordinationRepository,
      VisitCandidatePropertyRepository candidateRepository,
      CandidateAdmission candidateAdmission,
      Clock clock) {
    this.access = access;
    this.coordinationRepository = coordinationRepository;
    this.candidateRepository = candidateRepository;
    this.candidateAdmission = candidateAdmission;
    this.clock = clock;
  }

  @Transactional
  public CandidatePropertyAddResponse add(
      StaffPrincipal principal, Long coordinationId, Long expectedVersion, JsonNode body) {
    CustomerInput.requireObject(body, ADD_KEYS);
    Long propertyId = CustomerInput.requiredLong(body, "propertyId");
    Long requestedOrder = CustomerInput.optionalLong(body, "sortOrder");
    if (requestedOrder != null && requestedOrder < 1) {
      throw CustomerInput.invalid("sortOrder", FieldErrorCode.OUT_OF_RANGE, "1 이상이어야 합니다.");
    }
    VisitCoordination coordination = access.lock(principal, coordinationId);
    requireVersion(coordination, expectedVersion);
    if (!coordination.getStatus().acceptsCandidateAddition()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    if (candidateRepository.existsByCoordinationIdAndPropertyId(coordinationId, propertyId)) {
      throw CustomerInput.invalid("propertyId", FieldErrorCode.INVALID_VALUE, "이미 담긴 매물입니다.");
    }
    List<VisitCandidateProperty> existing =
        candidateRepository.findByCoordinationIdOrderBySortOrderAscIdAsc(coordinationId);
    if (existing.size() >= VisitCandidateProperty.MAX_PER_COORDINATION) {
      throw new BusinessException(ErrorCode.CANDIDATE_LIMIT_EXCEEDED);
    }
    PropertySummary property = candidateAdmission.admit(principal, propertyId);

    int order = existing.size() + 1;
    if (requestedOrder != null && requestedOrder < order) {
      order = requestedOrder.intValue();
      for (VisitCandidateProperty candidate : existing) {
        if (candidate.getSortOrder() >= order) {
          candidate.moveTo(candidate.getSortOrder() + 1);
        }
      }
    }
    VisitCandidateProperty saved =
        candidateRepository.saveAndFlush(
            VisitCandidateProperty.of(
                coordinationId,
                coordination.getOfficeId(),
                propertyId,
                property.officeId(),
                order));
    coordination.touch(clock.instant());
    VisitCoordination touched = coordinationRepository.saveAndFlush(coordination);
    return new CandidatePropertyAddResponse(
        saved.getId(),
        coordinationId,
        propertyId,
        saved.getSortOrder(),
        touched.getOfficeId(),
        touched.getVersion());
  }

  @Transactional
  public void remove(
      StaffPrincipal principal, Long coordinationId, Long candidateId, Long expectedVersion) {
    VisitCoordination coordination = access.lock(principal, coordinationId);
    requireVersion(coordination, expectedVersion);
    VisitCandidateProperty target =
        candidateRepository
            .findByIdAndCoordinationId(candidateId, coordinationId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!coordination.getStatus().acceptsCandidateRemoval()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    List<VisitCandidateProperty> candidates =
        candidateRepository.findByCoordinationIdOrderBySortOrderAscIdAsc(coordinationId);
    if (candidates.size() <= 1) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    candidateRepository.delete(target);
    candidateRepository.flush();
    int order = 1;
    for (VisitCandidateProperty candidate : candidates) {
      if (candidate.getId().equals(target.getId())) {
        continue;
      }
      candidate.moveTo(order++);
    }
    coordination.touch(clock.instant());
    coordinationRepository.saveAndFlush(coordination);
  }

  private static void requireVersion(VisitCoordination coordination, Long expectedVersion) {
    if (expectedVersion != null && !expectedVersion.equals(coordination.getVersion())) {
      throw BusinessException.versionConflict(coordination.getVersion());
    }
  }
}
