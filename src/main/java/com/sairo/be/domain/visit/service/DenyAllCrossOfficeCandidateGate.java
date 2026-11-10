package com.sairo.be.domain.visit.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class DenyAllCrossOfficeCandidateGate implements CrossOfficeCandidateGate {

  @Override
  public boolean isAllowed(
      Long granteeOfficeId, Long granteeMembershipId, Long propertyId, Long propertyOfficeId) {
    return false;
  }
}
