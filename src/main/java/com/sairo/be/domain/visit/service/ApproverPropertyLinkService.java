package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.legal.service.RetentionRuleLookup;
import com.sairo.be.domain.visit.dto.response.ApproverPropertyLinkIssueResponse;
import com.sairo.be.domain.visit.dto.response.ApproverPropertyLinkStatusResponse;
import com.sairo.be.domain.visit.entity.PropertyAccessApproval;
import com.sairo.be.domain.visit.entity.VisitApproverPropertyLink;
import com.sairo.be.domain.visit.repository.VisitApproverPropertyLinkRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.HmacSupport;
import com.sairo.be.global.support.PublicLinkIssuer;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class ApproverPropertyLinkService {

  static final String PATH_PREFIX = "/approver/";
  static final Duration MAX_LIFETIME = Duration.ofDays(7);

  private final ApprovalAccess access;
  private final VisitApproverPropertyLinkRepository linkRepository;
  private final PublicLinkIssuer linkIssuer;
  private final RetentionRuleLookup retentionRules;
  private final Clock clock;

  public ApproverPropertyLinkService(
      ApprovalAccess access,
      VisitApproverPropertyLinkRepository linkRepository,
      PublicLinkIssuer linkIssuer,
      RetentionRuleLookup retentionRules,
      Clock clock) {
    this.access = access;
    this.linkRepository = linkRepository;
    this.linkIssuer = linkIssuer;
    this.retentionRules = retentionRules;
    this.clock = clock;
  }

  @Transactional
  public ApproverPropertyLinkIssueResponse issue(
      StaffPrincipal principal, Long propertyId, Long approvalId) {
    access.requireAgentOrOwner(principal);
    PropertyAccessApproval approval = access.lock(principal, propertyId, approvalId);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    if (!approval.isUsableAt(now)) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    linkRepository
        .findFirstByApprovalIdAndRevokedAtIsNull(approvalId)
        .ifPresent(
            previous -> {
              previous.revoke(now);
              linkRepository.saveAndFlush(previous);
            });
    Instant maxExpiry = now.plus(MAX_LIFETIME);
    Instant expiresAt =
        approval.getValidUntil().isBefore(maxExpiry) ? approval.getValidUntil() : maxExpiry;
    VisitApproverPropertyLink saved =
        linkRepository.saveAndFlush(
            VisitApproverPropertyLink.issue(
                approvalId,
                HmacSupport.sha256Hex("pending:" + UUID.randomUUID()),
                principal.membershipId(),
                now,
                expiresAt,
                TimeRules.displayDate(expiresAt).plusDays(retentionDays())));
    PublicLinkIssuer.IssuedLink issued = linkIssuer.issue(saved.getId(), now, PATH_PREFIX);
    saved.bindToken(issued.tokenHash());
    linkRepository.saveAndFlush(saved);
    return new ApproverPropertyLinkIssueResponse(
        saved.getId(),
        approvalId,
        approval.getApprovalRole().name(),
        issued.token(),
        issued.url(),
        TimeRules.toDisplayOffset(saved.getIssuedAt()),
        TimeRules.toDisplayOffset(saved.getExpiresAt()));
  }

  @Transactional(readOnly = true)
  public ApproverPropertyLinkStatusResponse status(
      StaffPrincipal principal, Long propertyId, Long approvalId) {
    access.requireAgentOrOwner(principal);
    PropertyAccessApproval approval = access.view(principal, propertyId, approvalId);
    return linkRepository
        .findFirstByApprovalIdAndRevokedAtIsNull(approvalId)
        .map(
            link ->
                new ApproverPropertyLinkStatusResponse(
                    true,
                    link.getId(),
                    approval.getApprovalRole().name(),
                    TimeRules.toDisplayOffset(link.getIssuedAt()),
                    TimeRules.toDisplayOffset(link.getExpiresAt()),
                    link.getLastAccessedAt() == null
                        ? null
                        : TimeRules.toDisplayOffset(link.getLastAccessedAt())))
        .orElseGet(
            () ->
                new ApproverPropertyLinkStatusResponse(
                    false, null, approval.getApprovalRole().name(), null, null, null));
  }

  @Transactional
  public void revoke(StaffPrincipal principal, Long propertyId, Long approvalId) {
    access.requireAgentOrOwner(principal);
    access.lock(principal, propertyId, approvalId);
    VisitApproverPropertyLink link =
        linkRepository
            .findFirstByApprovalIdAndRevokedAtIsNull(approvalId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    link.revoke(clock.instant().truncatedTo(ChronoUnit.MICROS));
    linkRepository.saveAndFlush(link);
  }

  private int retentionDays() {
    return retentionRules
        .retentionDays(PropertyAccessApprovalService.RETENTION_RULE)
        .orElseThrow(() -> new IllegalStateException("승인자 개인정보 보관 규칙이 없습니다."));
  }
}
