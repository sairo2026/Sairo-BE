package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.property.service.PropertyReferenceService;
import com.sairo.be.domain.property.service.PropertyReferenceService.PropertySummary;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class CandidateAdmission {

  private final PropertyReferenceService propertyReferences;
  private final CrossOfficeCandidateGate crossOfficeGate;

  public CandidateAdmission(
      PropertyReferenceService propertyReferences, CrossOfficeCandidateGate crossOfficeGate) {
    this.propertyReferences = propertyReferences;
    this.crossOfficeGate = crossOfficeGate;
  }

  public PropertySummary admit(StaffPrincipal principal, Long propertyId) {
    PropertySummary property =
        propertyReferences
            .find(propertyId)
            .filter(found -> !found.archived())
            .orElseThrow(() -> new BusinessException(ErrorCode.CANDIDATE_NOT_ALLOWED));
    if (property.officeId().equals(principal.officeId())) {
      return property;
    }
    if (!crossOfficeGate.isAllowed(
        principal.officeId(), principal.membershipId(), propertyId, property.officeId())) {
      throw new BusinessException(ErrorCode.CANDIDATE_NOT_ALLOWED);
    }
    return property;
  }
}
