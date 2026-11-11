package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.ApproverPreferredBrokerListResponse;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.ApproverPreferredBrokerResponse;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.PreferredBrokerCandidate;
import com.sairo.be.domain.visit.dto.response.VisitReviewResponses.PreferredBrokerItem;
import com.sairo.be.domain.visit.repository.MembershipNameRepository;
import com.sairo.be.domain.visit.repository.PreferredBrokerRepository;
import com.sairo.be.domain.visit.repository.PreferredBrokerRepository.Candidate;
import com.sairo.be.domain.visit.repository.PreferredBrokerRepository.Preferred;
import com.sairo.be.domain.visit.repository.PreferredBrokerRepository.Source;
import com.sairo.be.domain.visit.service.ApproverReviewContext.Opened;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class PreferredBrokerService {

  public static final int MAX_ACTIVE = 5;
  private static final Set<String> KEYS =
      Set.of("sourceRequestId", "sourceScheduleItemId", "acknowledgedNoticeVersion");

  private final ApproverReviewContext context;
  private final ApproverReviewService reviewService;
  private final PreferredBrokerRepository preferredRepository;
  private final MembershipNameRepository nameRepository;
  private final AdvisoryLock advisoryLock;
  private final Clock clock;

  public PreferredBrokerService(
      ApproverReviewContext context,
      ApproverReviewService reviewService,
      PreferredBrokerRepository preferredRepository,
      MembershipNameRepository nameRepository,
      AdvisoryLock advisoryLock,
      Clock clock) {
    this.context = context;
    this.reviewService = reviewService;
    this.preferredRepository = preferredRepository;
    this.nameRepository = nameRepository;
    this.advisoryLock = advisoryLock;
    this.clock = clock;
  }

  @Transactional
  public ApproverPreferredBrokerListResponse list(String token, String clientIp) {
    return list(context.open(token, clientIp));
  }

  @Transactional(readOnly = true)
  public ApproverPreferredBrokerListResponse listForProfile(Long profileId, Long approvalId) {
    return list(context.openForProfile(profileId, approvalId));
  }

  private ApproverPreferredBrokerListResponse list(Opened opened) {
    List<Preferred> active =
        opened.dataApprovalId().map(preferredRepository::active).orElse(List.of());
    List<Candidate> candidates =
        opened.dataApprovalId().map(preferredRepository::candidates).orElse(List.of());
    List<Long> members = new ArrayList<>();
    active.forEach(p -> members.add(p.brokerMembershipId()));
    candidates.forEach(c -> members.add(c.membershipId()));
    Map<Long, String> names = nameRepository.displayNames(members.stream().distinct().toList());
    Map<Long, String> offices = new HashMap<>();
    List<PreferredBrokerItem> items =
        active.stream()
            .map(
                p ->
                    new PreferredBrokerItem(
                        p.id(),
                        offices.computeIfAbsent(p.brokerOfficeId(), context::officeName),
                        names.getOrDefault(p.brokerMembershipId(), ""),
                        TimeRules.toDisplayOffset(p.designatedAt())))
            .toList();
    List<PreferredBrokerCandidate> candidateItems =
        candidates.stream()
            .map(
                c ->
                    new PreferredBrokerCandidate(
                        c.sourceRequestId(),
                        c.sourceScheduleItemId(),
                        offices.computeIfAbsent(c.officeId(), context::officeName),
                        names.getOrDefault(c.membershipId(), "")))
            .toList();
    return new ApproverPreferredBrokerListResponse(
        MAX_ACTIVE, items, candidateItems, reviewService.notice());
  }

  @Transactional
  public ApproverPreferredBrokerResponse designate(String token, JsonNode body, String clientIp) {
    return designate(context.openForWrite(token, clientIp), body);
  }

  @Transactional
  public ApproverPreferredBrokerResponse designateForProfile(
      Long profileId, Long approvalId, JsonNode body) {
    return designate(context.openForProfileWrite(profileId, approvalId), body);
  }

  private ApproverPreferredBrokerResponse designate(Opened opened, JsonNode body) {
    CustomerInput.requireObject(body, KEYS);
    Long requestId = CustomerInput.optionalLong(body, "sourceRequestId");
    Long itemId = CustomerInput.optionalLong(body, "sourceScheduleItemId");
    if ((requestId == null) == (itemId == null)) {
      throw CustomerInput.invalid(
          "sourceRequestId", FieldErrorCode.INVALID_VALUE, "신청 번호와 방문 번호 가운데 하나만 보내야 합니다.");
    }
    CustomerInput.requiredText(body, "acknowledgedNoticeVersion", 50, false);
    reviewService.requireNotice(body);
    Long approvalId = opened.approval().getId();
    advisoryLock.lockForTransaction(ApprovalAccess.lockKey(approvalId));
    Source source =
        (requestId != null
                ? preferredRepository.requestSource(requestId, approvalId)
                : preferredRepository.visitSource(itemId, approvalId))
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (preferredRepository.isActive(approvalId, source.membershipId())) {
      throw new BusinessException(ErrorCode.PREFERRED_BROKER_DUPLICATE);
    }
    if (preferredRepository.countActive(approvalId) >= MAX_ACTIVE) {
      throw new BusinessException(ErrorCode.PREFERRED_BROKER_LIMIT_EXCEEDED);
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Long id =
        preferredRepository.insert(
            approvalId,
            source.membershipId(),
            opened.linkId(),
            opened.profileId(),
            requestId,
            itemId,
            now);
    return response(
        preferredRepository
            .lock(id, approvalId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)));
  }

  @Transactional
  public ApproverPreferredBrokerResponse release(
      String token, Long preferredBrokerId, String clientIp) {
    return release(context.openForWrite(token, clientIp), preferredBrokerId);
  }

  @Transactional
  public ApproverPreferredBrokerResponse releaseForProfile(
      Long profileId, Long approvalId, Long preferredBrokerId) {
    return release(context.openForProfileWrite(profileId, approvalId), preferredBrokerId);
  }

  private ApproverPreferredBrokerResponse release(Opened opened, Long preferredBrokerId) {
    Long approvalId = opened.approval().getId();
    advisoryLock.lockForTransaction(ApprovalAccess.lockKey(approvalId));
    Preferred preferred =
        preferredRepository
            .lock(preferredBrokerId, approvalId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (preferred.releasedAt() != null) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    preferredRepository.release(preferred.id(), opened.linkId(), opened.profileId(), now);
    return response(
        preferredRepository
            .lock(preferred.id(), approvalId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)));
  }

  private ApproverPreferredBrokerResponse response(Preferred preferred) {
    return new ApproverPreferredBrokerResponse(
        preferred.id(),
        context.officeName(preferred.brokerOfficeId()),
        nameRepository
            .displayNames(List.of(preferred.brokerMembershipId()))
            .getOrDefault(preferred.brokerMembershipId(), ""),
        TimeRules.toDisplayOffset(preferred.designatedAt()),
        preferred.releasedAt() == null ? null : TimeRules.toDisplayOffset(preferred.releasedAt()));
  }
}
