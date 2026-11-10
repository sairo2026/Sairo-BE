package com.sairo.be.domain.visit.service;

public interface CrossOfficeCandidateGate {

  boolean isAllowed(
      Long granteeOfficeId, Long granteeMembershipId, Long propertyId, Long propertyOfficeId);
}
