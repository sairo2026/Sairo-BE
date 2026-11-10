package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.property.service.ManagingMemberDirectory;
import com.sairo.be.domain.property.service.PropertyReferenceService;
import com.sairo.be.domain.property.service.PropertyReferenceService.PropertySummary;
import com.sairo.be.domain.visit.dto.response.PublicVisitLinkResponse;
import com.sairo.be.domain.visit.entity.VisitCandidateProperty;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.entity.VisitCustomerLink;
import com.sairo.be.domain.visit.entity.VisitCustomerTimeSlot;
import com.sairo.be.domain.visit.repository.VisitCandidatePropertyRepository;
import com.sairo.be.domain.visit.repository.VisitCoordinationRepository;
import com.sairo.be.domain.visit.repository.VisitCustomerLinkRepository;
import com.sairo.be.domain.visit.repository.VisitCustomerTimeSlotRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.OfficeDirectory;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class PublicCustomerLinkQueryService {

  static final String PURPOSE = "방문 일정 조율";
  static final String COLLECTED_ITEM = "방문 가능 시간";
  static final String RETENTION_PERIOD = "고객 개인정보 보유기한까지";

  private final PublicLinkResolver resolver;
  private final VisitCustomerLinkRepository linkRepository;
  private final VisitCoordinationRepository coordinationRepository;
  private final VisitCandidatePropertyRepository candidateRepository;
  private final VisitCustomerTimeSlotRepository slotRepository;
  private final PropertyReferenceService propertyReferences;
  private final ManagingMemberDirectory memberDirectory;
  private final OfficeDirectory officeDirectory;
  private final CustomerLinkNoticeProvider noticeProvider;
  private final Clock clock;

  public PublicCustomerLinkQueryService(
      PublicLinkResolver resolver,
      VisitCustomerLinkRepository linkRepository,
      VisitCoordinationRepository coordinationRepository,
      VisitCandidatePropertyRepository candidateRepository,
      VisitCustomerTimeSlotRepository slotRepository,
      PropertyReferenceService propertyReferences,
      ManagingMemberDirectory memberDirectory,
      OfficeDirectory officeDirectory,
      CustomerLinkNoticeProvider noticeProvider,
      Clock clock) {
    this.resolver = resolver;
    this.linkRepository = linkRepository;
    this.coordinationRepository = coordinationRepository;
    this.candidateRepository = candidateRepository;
    this.slotRepository = slotRepository;
    this.propertyReferences = propertyReferences;
    this.memberDirectory = memberDirectory;
    this.officeDirectory = officeDirectory;
    this.noticeProvider = noticeProvider;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PublicVisitLinkResponse get(String token, String clientIp) {
    VisitCustomerLink link =
        linkRepository
            .findById(resolver.resolve(token, clientIp).getId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    VisitCoordination coordination =
        coordinationRepository
            .findById(link.getCoordinationId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    if (coordination.getStatus().acceptsCustomerResponse() && link.isExpiredAt(clock.instant())) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_EXPIRED);
    }
    OfficeDirectory.OfficeSummary office =
        officeDirectory
            .find(coordination.getOfficeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    ManagingMemberDirectory.ManagingMember manager =
        memberDirectory
            .find(coordination.getManagerMembershipId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    List<VisitCandidateProperty> candidates =
        candidateRepository.findByCoordinationIdOrderBySortOrderAscIdAsc(coordination.getId());
    Map<Long, PropertySummary> properties =
        propertyReferences.findAll(
            candidates.stream().map(VisitCandidateProperty::getPropertyId).toList());
    List<VisitCustomerTimeSlot> slots =
        link.getResponseVersion() == 0
            ? List.of()
            : slotRepository.findByCustomerLinkIdAndResponseVersionOrderByStartsAtAscIdAsc(
                link.getId(), link.getResponseVersion());
    CustomerLinkNoticeProvider.Notice notice = noticeProvider.current();
    return new PublicVisitLinkResponse(
        "CUSTOMER",
        office.name(),
        manager.displayName(),
        legalRole(manager.role()),
        TimeRules.toDisplayOffset(link.getExpiresAt()),
        office.contactPhone(),
        new PublicVisitLinkResponse.PrivacyNotice(
            PURPOSE,
            List.of(COLLECTED_ITEM),
            RETENTION_PERIOD,
            notice.noticeCode(),
            notice.noticeVersion(),
            notice.retentionDays()),
        new PublicVisitLinkResponse.CandidateWindow(
            coordination.getPeriodStartDate(), coordination.getPeriodEndDate()),
        candidates.stream()
            .map(
                candidate -> {
                  PropertySummary property = properties.get(candidate.getPropertyId());
                  return new PublicVisitLinkResponse.Candidate(
                      candidate.getId(),
                      property == null ? "" : property.name(),
                      property == null ? "" : property.district());
                })
            .toList(),
        slots.stream()
            .map(
                slot ->
                    new PublicVisitLinkResponse.TimeSlot(
                        TimeRules.toDisplayOffset(slot.getStartsAt()),
                        TimeRules.toDisplayOffset(slot.getEndsAt())))
            .toList(),
        link.getResponseVersion(),
        coordination.getStatus().name());
  }

  static String legalRole(String membershipRole) {
    return switch (membershipRole) {
      case "OWNER" -> "REPRESENTATIVE_AGENT";
      case "AGENT" -> "AFFILIATED_AGENT";
      default -> "ASSISTANT";
    };
  }
}
