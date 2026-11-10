package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.property.service.PropertyReferenceService;
import com.sairo.be.domain.visit.entity.PropertyAccessApproval;
import com.sairo.be.domain.visit.repository.PropertyAccessApprovalRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StaffRole;
import com.sairo.be.global.support.AdvisoryLock;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class ApprovalAccess {

  public static String lockKey(Long approvalId) {
    return "visit:approval:" + approvalId;
  }

  private final PropertyReferenceService propertyReferences;
  private final PropertyAccessApprovalRepository approvalRepository;
  private final AdvisoryLock advisoryLock;

  public ApprovalAccess(
      PropertyReferenceService propertyReferences,
      PropertyAccessApprovalRepository approvalRepository,
      AdvisoryLock advisoryLock) {
    this.propertyReferences = propertyReferences;
    this.approvalRepository = approvalRepository;
    this.advisoryLock = advisoryLock;
  }

  public void requireOwnProperty(StaffPrincipal principal, Long propertyId) {
    propertyReferences
        .find(propertyId)
        .filter(summary -> summary.officeId().equals(principal.officeId()))
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  public PropertyAccessApproval view(StaffPrincipal principal, Long propertyId, Long approvalId) {
    requireOwnProperty(principal, propertyId);
    return approvalRepository
        .findByIdAndPropertyId(approvalId, propertyId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  public PropertyAccessApproval lock(StaffPrincipal principal, Long propertyId, Long approvalId) {
    requireOwnProperty(principal, propertyId);
    advisoryLock.lockForTransaction(lockKey(approvalId));
    return approvalRepository
        .findForUpdate(approvalId, propertyId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  public void requireAgentOrOwner(StaffPrincipal principal) {
    if (principal.role() == StaffRole.DEPUTY) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
  }
}
