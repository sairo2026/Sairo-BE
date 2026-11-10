package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.repository.VisitCoordinationRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StaffRole;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class CoordinationAccess {

  private final VisitCoordinationRepository repository;

  public CoordinationAccess(VisitCoordinationRepository repository) {
    this.repository = repository;
  }

  public VisitCoordination view(StaffPrincipal principal, Long coordinationId) {
    VisitCoordination coordination =
        repository
            .findByIdAndOfficeId(coordinationId, principal.officeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    requireInScope(principal, coordination);
    return coordination;
  }

  public VisitCoordination lock(StaffPrincipal principal, Long coordinationId) {
    VisitCoordination coordination =
        repository
            .findForUpdate(coordinationId, principal.officeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    requireInScope(principal, coordination);
    return coordination;
  }

  public Long managerScope(StaffPrincipal principal) {
    return principal.role() == StaffRole.DEPUTY ? principal.membershipId() : null;
  }

  private void requireInScope(StaffPrincipal principal, VisitCoordination coordination) {
    if (principal.role() == StaffRole.DEPUTY
        && !coordination.getManagerMembershipId().equals(principal.membershipId())) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
  }
}
