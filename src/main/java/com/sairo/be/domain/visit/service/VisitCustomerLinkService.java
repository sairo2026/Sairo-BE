package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.legal.service.RetentionRuleLookup;
import com.sairo.be.domain.visit.dto.response.CustomerLinkIssueResponse;
import com.sairo.be.domain.visit.dto.response.CustomerLinkReissueResponse;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.entity.VisitCustomerLink;
import com.sairo.be.domain.visit.entity.VisitCustomerTimeSlot;
import com.sairo.be.domain.visit.repository.VisitCustomerLinkRepository;
import com.sairo.be.domain.visit.repository.VisitCustomerTimeSlotRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.HmacSupport;
import com.sairo.be.global.support.PublicLinkIssuer;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class VisitCustomerLinkService {

  static final String PATH_PREFIX = "/visit-responses/";
  static final String RETENTION_RULE = "GUEST_INPUT_AFTER_APPROVAL_END";

  private final CoordinationAccess access;
  private final VisitCustomerLinkRepository linkRepository;
  private final VisitCustomerTimeSlotRepository slotRepository;
  private final PublicLinkIssuer linkIssuer;
  private final RetentionRuleLookup retentionRules;
  private final Clock clock;

  public VisitCustomerLinkService(
      CoordinationAccess access,
      VisitCustomerLinkRepository linkRepository,
      VisitCustomerTimeSlotRepository slotRepository,
      PublicLinkIssuer linkIssuer,
      RetentionRuleLookup retentionRules,
      Clock clock) {
    this.access = access;
    this.linkRepository = linkRepository;
    this.slotRepository = slotRepository;
    this.linkIssuer = linkIssuer;
    this.retentionRules = retentionRules;
    this.clock = clock;
  }

  private record Issued(VisitCustomerLink link, PublicLinkIssuer.IssuedLink issued) {}

  @Transactional
  public CustomerLinkIssueResponse issue(StaffPrincipal principal, Long coordinationId) {
    VisitCoordination coordination = access.lock(principal, coordinationId);
    requireIssuable(coordination);
    if (linkRepository.findFirstByCoordinationIdAndRevokedAtIsNull(coordinationId).isPresent()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Issued created = create(coordination);
    VisitCustomerLink link = created.link();
    return new CustomerLinkIssueResponse(
        link.getId(),
        created.issued().token(),
        created.issued().url(),
        TimeRules.toDisplayOffset(link.getIssuedAt()),
        TimeRules.toDisplayOffset(link.getExpiresAt()));
  }

  @Transactional
  public CustomerLinkReissueResponse reissue(StaffPrincipal principal, Long coordinationId) {
    VisitCoordination coordination = access.lock(principal, coordinationId);
    requireIssuable(coordination);
    VisitCustomerLink previous =
        linkRepository
            .findFirstByCoordinationIdAndRevokedAtIsNull(coordinationId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TRANSITION));
    previous.revoke(clock.instant());
    linkRepository.saveAndFlush(previous);

    Issued created = create(coordination);
    VisitCustomerLink link = created.link();
    link.inheritResponse(previous);
    linkRepository.saveAndFlush(link);
    carryOverSlots(previous, link);
    return new CustomerLinkReissueResponse(
        link.getId(),
        created.issued().token(),
        created.issued().url(),
        TimeRules.toDisplayOffset(link.getIssuedAt()),
        TimeRules.toDisplayOffset(link.getExpiresAt()));
  }

  private void requireIssuable(VisitCoordination coordination) {
    if (!coordination.getStatus().acceptsCustomerResponse()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
  }

  private Issued create(VisitCoordination coordination) {
    Instant now = clock.instant();
    if (!TimeRules.endOfDisplayDayExclusive(coordination.getPeriodEndDate()).isAfter(now)) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant expiresAt = CustomerLinkExpiry.expiresAt(now, coordination.getPeriodStartDate());
    LocalDate retentionUntil = TimeRules.displayDate(expiresAt).plusDays(retentionDays());
    VisitCustomerLink saved =
        linkRepository.saveAndFlush(
            VisitCustomerLink.issue(
                coordination.getId(), placeholderHash(), now, expiresAt, retentionUntil));
    PublicLinkIssuer.IssuedLink issued = linkIssuer.issue(saved.getId(), now, PATH_PREFIX);
    saved.bindToken(issued.tokenHash());
    return new Issued(linkRepository.saveAndFlush(saved), issued);
  }

  private void carryOverSlots(VisitCustomerLink previous, VisitCustomerLink next) {
    if (previous.getResponseVersion() == 0) {
      return;
    }
    List<VisitCustomerTimeSlot> slots =
        slotRepository.findByCustomerLinkIdAndResponseVersionOrderByStartsAtAscIdAsc(
            previous.getId(), previous.getResponseVersion());
    for (VisitCustomerTimeSlot slot : slots) {
      slotRepository.save(
          VisitCustomerTimeSlot.of(
              next.getId(), next.getResponseVersion(), slot.getStartsAt(), slot.getEndsAt()));
    }
    slotRepository.flush();
  }

  private int retentionDays() {
    return retentionRules
        .retentionDays(RETENTION_RULE)
        .orElseThrow(() -> new IllegalStateException("비회원 링크 입력 데이터 보관 규칙이 없습니다."));
  }

  private static String placeholderHash() {
    return HmacSupport.sha256Hex("pending:" + UUID.randomUUID());
  }
}
