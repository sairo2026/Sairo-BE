package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.property.service.PropertyReferenceService;
import com.sairo.be.domain.visit.entity.PropertyAccessApproval;
import com.sairo.be.domain.visit.repository.PropertyAccessApprovalRepository;
import com.sairo.be.domain.visit.repository.VisitApproverPropertyLinkRepository.LinkHead;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.OfficeDirectory;
import java.time.Clock;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class ApproverReviewContext {

  public record Opened(
      LinkHead link, PropertyAccessApproval approval, Optional<Long> dataApprovalId) {

    public boolean writable() {
      return approval.isPrimary();
    }
  }

  private final PublicApproverLinkResolver linkResolver;
  private final ApproverLinkAccessRecorder accessRecorder;
  private final PropertyAccessApprovalRepository approvalRepository;
  private final PropertyReferenceService propertyReferences;
  private final OfficeDirectory officeDirectory;
  private final Clock clock;

  public ApproverReviewContext(
      PublicApproverLinkResolver linkResolver,
      ApproverLinkAccessRecorder accessRecorder,
      PropertyAccessApprovalRepository approvalRepository,
      PropertyReferenceService propertyReferences,
      OfficeDirectory officeDirectory,
      Clock clock) {
    this.linkResolver = linkResolver;
    this.accessRecorder = accessRecorder;
    this.approvalRepository = approvalRepository;
    this.propertyReferences = propertyReferences;
    this.officeDirectory = officeDirectory;
    this.clock = clock;
  }

  public Opened open(String token, String clientIp) {
    LinkHead head = linkResolver.resolve(token, clientIp);
    accessRecorder.record(head.getId());
    PropertyAccessApproval approval =
        approvalRepository
            .findById(head.getApprovalId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    Optional<Long> dataApproval =
        approval.isPrimary()
            ? Optional.of(approval.getId())
            : approvalRepository
                .findUsablePrimaries(approval.getPropertyId(), clock.instant())
                .stream()
                .findFirst()
                .map(PropertyAccessApproval::getId);
    return new Opened(head, approval, dataApproval);
  }

  public Opened openForWrite(String token, String clientIp) {
    Opened opened = open(token, clientIp);
    if (!opened.writable()) {
      throw new BusinessException(ErrorCode.APPROVER_READ_ONLY);
    }
    return opened;
  }

  public String propertyAddress(PropertyAccessApproval approval) {
    return propertyReferences
        .find(approval.getPropertyId())
        .map(PropertyReferenceService.PropertySummary::address)
        .filter(address -> address != null)
        .orElse("");
  }

  public String officeName(Long officeId) {
    return officeDirectory.find(officeId).map(OfficeDirectory.OfficeSummary::name).orElse("");
  }
}
