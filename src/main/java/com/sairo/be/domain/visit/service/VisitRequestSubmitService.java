package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.customer.service.CustomerReferenceService;
import com.sairo.be.domain.property.service.PropertyReferenceService;
import com.sairo.be.domain.property.service.PropertyReferenceService.PropertySummary;
import com.sairo.be.domain.visit.dto.response.VisitRequestResponse;
import com.sairo.be.domain.visit.entity.ConditionStatus;
import com.sairo.be.domain.visit.entity.CoordinationStatus;
import com.sairo.be.domain.visit.entity.PropertyAccessApproval;
import com.sairo.be.domain.visit.entity.RequestStatus;
import com.sairo.be.domain.visit.entity.StatusChangeSource;
import com.sairo.be.domain.visit.entity.VisitApproverCondition;
import com.sairo.be.domain.visit.entity.VisitCandidateProperty;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.entity.VisitCustomerLink;
import com.sairo.be.domain.visit.entity.VisitRequest;
import com.sairo.be.domain.visit.mapper.VisitRequestMapper;
import com.sairo.be.domain.visit.repository.ApproverConditionRowRepository;
import com.sairo.be.domain.visit.repository.ConfirmedVisitRepository;
import com.sairo.be.domain.visit.repository.PropertyAccessApprovalRepository;
import com.sairo.be.domain.visit.repository.VisitApproverConditionRepository;
import com.sairo.be.domain.visit.repository.VisitCandidatePropertyRepository;
import com.sairo.be.domain.visit.repository.VisitCustomerLinkRepository;
import com.sairo.be.domain.visit.repository.VisitCustomerTimeSlotRepository;
import com.sairo.be.domain.visit.repository.VisitRequestRepository;
import com.sairo.be.domain.visit.repository.VisitRequestStatusHistoryRepository;
import com.sairo.be.domain.visit.service.VisitRequestChecker.Slot;
import com.sairo.be.domain.visit.service.VisitRequestChecker.Subject;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.port.ApplyGrantPort;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.RequestRateLimiter;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class VisitRequestSubmitService {

  private static final Set<String> KEYS =
      Set.of(
          "candidatePropertyId",
          "requestedStartAt",
          "requestedEndAt",
          "expectedDurationMinutes",
          "partySize",
          "requestNote");
  private static final int NOTE_MAX = 500;
  private static final long QUOTA_RETRY_SECONDS = 60L;
  private static final Pattern PHONE_LIKE =
      Pattern.compile("\\d{2,3}[-\\s.]?\\d{3,4}[-\\s.]?\\d{4}");

  private final CoordinationAccess access;
  private final VisitCandidatePropertyRepository candidateRepository;
  private final PropertyReferenceService propertyReferences;
  private final CustomerReferenceService customerReferences;
  private final PropertyAccessApprovalRepository approvalRepository;
  private final VisitApproverConditionRepository conditionRepository;
  private final ApproverConditionRowRepository rowRepository;
  private final ConfirmedVisitRepository confirmedVisits;
  private final VisitCustomerLinkRepository linkRepository;
  private final VisitCustomerTimeSlotRepository slotRepository;
  private final VisitRequestRepository requestRepository;
  private final VisitRequestStatusHistoryRepository historyRepository;
  private final CoordinationStatusService coordinationStatus;
  private final ApplyGrantPort applyGrants;
  private final AdvisoryLock advisoryLock;
  private final RequestRateLimiter rateLimiter;
  private final EntityManager entityManager;
  private final Clock clock;

  public VisitRequestSubmitService(
      CoordinationAccess access,
      VisitCandidatePropertyRepository candidateRepository,
      PropertyReferenceService propertyReferences,
      CustomerReferenceService customerReferences,
      PropertyAccessApprovalRepository approvalRepository,
      VisitApproverConditionRepository conditionRepository,
      ApproverConditionRowRepository rowRepository,
      ConfirmedVisitRepository confirmedVisits,
      VisitCustomerLinkRepository linkRepository,
      VisitCustomerTimeSlotRepository slotRepository,
      VisitRequestRepository requestRepository,
      VisitRequestStatusHistoryRepository historyRepository,
      CoordinationStatusService coordinationStatus,
      ApplyGrantPort applyGrants,
      AdvisoryLock advisoryLock,
      RequestRateLimiter rateLimiter,
      EntityManager entityManager,
      Clock clock) {
    this.access = access;
    this.candidateRepository = candidateRepository;
    this.propertyReferences = propertyReferences;
    this.customerReferences = customerReferences;
    this.approvalRepository = approvalRepository;
    this.conditionRepository = conditionRepository;
    this.rowRepository = rowRepository;
    this.confirmedVisits = confirmedVisits;
    this.linkRepository = linkRepository;
    this.slotRepository = slotRepository;
    this.requestRepository = requestRepository;
    this.historyRepository = historyRepository;
    this.coordinationStatus = coordinationStatus;
    this.applyGrants = applyGrants;
    this.advisoryLock = advisoryLock;
    this.rateLimiter = rateLimiter;
    this.entityManager = entityManager;
    this.clock = clock;
  }

  public static String memberQuotaLockKey(Long membershipId) {
    return "visit:member-quota:" + membershipId;
  }

  @Transactional
  public VisitRequestResponse submit(StaffPrincipal principal, Long coordinationId, JsonNode body) {
    Input input = parse(body);
    VisitCoordination preview = access.view(principal, coordinationId);
    rateLimiter.hit(
        VisitRateLimits.REQUEST_SUBMIT_MEMBER, String.valueOf(principal.membershipId()));
    requireAcceptingStatus(preview.getStatus());
    VisitCandidateProperty candidate =
        candidateRepository
            .findByIdAndCoordinationId(input.candidatePropertyId(), coordinationId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    PropertySummary property =
        propertyReferences
            .find(candidate.getPropertyId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (property.archived()) {
      throw new BusinessException(ErrorCode.PROPERTY_ARCHIVED);
    }
    boolean crossOffice = !property.officeId().equals(principal.officeId());
    Instant now = clock.instant();
    PropertyAccessApproval approval = usableApproval(property.propertyId(), now, crossOffice);
    entityManager.clear();

    advisoryLock.lockAllForTransaction(
        List.of(
            memberQuotaLockKey(principal.membershipId()),
            ApprovalAccess.lockKey(approval.getId())));
    PropertyAccessApproval lockedApproval =
        approvalRepository
            .findForUpdateById(approval.getId())
            .filter(found -> found.isPrimary() && found.isUsableAt(now))
            .orElseThrow(
                () ->
                    new BusinessException(
                        crossOffice
                            ? ErrorCode.APPLY_GRANT_INACTIVE
                            : ErrorCode.ACCESS_APPROVAL_MISSING));
    Long grantId = null;
    if (crossOffice) {
      grantId =
          applyGrants
              .findActiveGrantId(
                  principal.officeId(),
                  principal.membershipId(),
                  lockedApproval.getId(),
                  property.propertyId())
              .orElseThrow(() -> new BusinessException(ErrorCode.APPLY_GRANT_INACTIVE));
    }
    VisitCoordination coordination = access.lock(principal, coordinationId);
    requireAcceptingStatus(coordination.getStatus());

    VisitApproverCondition condition =
        conditionRepository
            .findFirstByApprovalIdAndStatus(lockedApproval.getId(), ConditionStatus.ACTIVE)
            .orElseThrow(() -> new BusinessException(ErrorCode.APPROVER_CONDITION_MISSING));
    if (!now.isBefore(condition.getAppliesUntil())) {
      throw new BusinessException(ErrorCode.APPROVER_CONDITION_EXPIRED);
    }
    requireNoteWithoutCustomerData(input.requestNote(), coordination.getCustomerId());
    if (requestRepository.existsByCandidatePropertyIdAndStatusIn(
        candidate.getId(), RequestStatus.ACTIVE)) {
      throw new BusinessException(ErrorCode.VISIT_REQUEST_DUPLICATE_ACTIVE);
    }
    long memberActive =
        requestRepository.countByApplicantMembershipIdAndStatusIn(
            principal.membershipId(), RequestStatus.ACTIVE);
    if (memberActive >= VisitRequest.MEMBER_ACTIVE_LIMIT) {
      throw BusinessException.visitRequestQuotaExceeded(
          QUOTA_RETRY_SECONDS,
          BusinessException.QuotaScope.MEMBERSHIP,
          VisitRequest.MEMBER_ACTIVE_LIMIT,
          memberActive);
    }
    long approvalActive =
        requestRepository.countByApprovalIdAndStatusIn(
            lockedApproval.getId(), RequestStatus.ACTIVE);
    if (approvalActive >= VisitRequest.APPROVAL_ACTIVE_LIMIT) {
      throw BusinessException.visitRequestQuotaExceeded(
          QUOTA_RETRY_SECONDS,
          BusinessException.QuotaScope.APPROVAL,
          VisitRequest.APPROVAL_ACTIVE_LIMIT,
          null);
    }

    List<String> violated =
        VisitRequestChecker.violatedRules(
            specOf(condition),
            new Subject(
                input.start(),
                input.end(),
                coordinationId,
                principal.membershipId(),
                lockedApproval.getValidFrom(),
                lockedApproval.getValidUntil()),
            confirmedVisits.findByApproval(lockedApproval.getId()),
            customerSlots(coordinationId),
            now);
    if (!violated.isEmpty()) {
      throw BusinessException.withDetails(
          ErrorCode.APPROVER_CONDITION_VIOLATED, Map.of("violatedRules", violated));
    }

    VisitRequest saved =
        requestRepository.saveAndFlush(
            VisitRequest.submitNew(
                condition.getId(),
                lockedApproval.getId(),
                property.propertyId(),
                property.officeId(),
                grantId,
                principal.officeId(),
                principal.membershipId(),
                coordinationId,
                candidate.getId(),
                input.start(),
                input.end(),
                input.durationMinutes(),
                input.partySize(),
                input.requestNote(),
                now));
    historyRepository.save(saved.openingHistory(principal.membershipId()));
    if (coordination.getStatus() == CoordinationStatus.FINAL_CONFIRMATION_REQUIRED) {
      coordinationStatus.move(
          coordination,
          CoordinationStatus.APPLYING,
          StatusChangeSource.STAFF,
          principal.membershipId(),
          "방문 신청 제출");
    }
    return VisitRequestMapper.toResponse(saved);
  }

  private PropertyAccessApproval usableApproval(Long propertyId, Instant now, boolean crossOffice) {
    return approvalRepository.findUsablePrimaries(propertyId, now).stream()
        .findFirst()
        .orElseThrow(
            () ->
                new BusinessException(
                    crossOffice
                        ? ErrorCode.APPLY_GRANT_INACTIVE
                        : ErrorCode.ACCESS_APPROVAL_MISSING));
  }

  private static void requireAcceptingStatus(CoordinationStatus status) {
    if (status != CoordinationStatus.FINAL_CONFIRMATION_REQUIRED
        && status != CoordinationStatus.APPLYING) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
  }

  private void requireNoteWithoutCustomerData(String note, Long customerId) {
    if (note == null) {
      return;
    }
    String name =
        customerReferences.names(List.of(customerId)).getOrDefault(customerId, "").strip();
    boolean hasName = name.length() >= 2 && note.contains(name);
    if (hasName || PHONE_LIKE.matcher(note).find()) {
      throw CustomerInput.invalid(
          "requestNote", FieldErrorCode.INVALID_VALUE, "고객 이름과 연락처는 넣을 수 없습니다.");
    }
  }

  private List<Slot> customerSlots(Long coordinationId) {
    Optional<VisitCustomerLink> link =
        linkRepository.findFirstByCoordinationIdOrderByIdDesc(coordinationId);
    return link.filter(found -> found.getResponseVersion() > 0)
        .map(
            found ->
                slotRepository
                    .findByCustomerLinkIdAndResponseVersionOrderByStartsAtAscIdAsc(
                        found.getId(), found.getResponseVersion())
                    .stream()
                    .map(slot -> new Slot(slot.getStartsAt(), slot.getEndsAt()))
                    .toList())
        .orElse(List.of());
  }

  private ConditionSpec specOf(VisitApproverCondition condition) {
    return new ConditionSpec(
        condition.getAppliesFrom(),
        condition.getAppliesUntil(),
        condition.getMaxVisitsPerDay(),
        condition.getMinGapMinutes(),
        condition.getMinLeadMinutes(),
        condition.getSingleVisitMaxMinutes(),
        condition.getGroupingPreference(),
        rowRepository.windowsOf(condition.getId()),
        rowRepository.dateRulesOf(condition.getId()));
  }

  private Input parse(JsonNode body) {
    CustomerInput.requireObject(body, KEYS);
    Long candidateId = CustomerInput.requiredLong(body, "candidatePropertyId");
    OffsetDateTime start = CustomerInput.requiredDateTime(body, "requestedStartAt");
    OffsetDateTime end = CustomerInput.requiredDateTime(body, "requestedEndAt");
    Long duration = CustomerInput.requiredLong(body, "expectedDurationMinutes");
    Long party = CustomerInput.optionalLong(body, "partySize");
    String note = CustomerInput.optionalText(body, "requestNote", NOTE_MAX, true);
    if (!end.isAfter(start)) {
      throw CustomerInput.invalid("requestedEndAt", FieldErrorCode.OUT_OF_RANGE, "시작보다 늦어야 합니다.");
    }
    requireFiveMinuteGrid("requestedStartAt", start);
    requireFiveMinuteGrid("requestedEndAt", end);
    if (!start.toInstant().isAfter(clock.instant())) {
      throw CustomerInput.invalid("requestedStartAt", FieldErrorCode.OUT_OF_RANGE, "지금보다 늦어야 합니다.");
    }
    if (duration < 1 || duration > Integer.MAX_VALUE) {
      throw CustomerInput.invalid(
          "expectedDurationMinutes", FieldErrorCode.OUT_OF_RANGE, "1 이상이어야 합니다.");
    }
    if (party != null && (party < 1 || party > Integer.MAX_VALUE)) {
      throw CustomerInput.invalid("partySize", FieldErrorCode.OUT_OF_RANGE, "1 이상이어야 합니다.");
    }
    return new Input(
        candidateId,
        start.toInstant(),
        end.toInstant(),
        duration.intValue(),
        party == null ? null : party.intValue(),
        note);
  }

  private static void requireFiveMinuteGrid(String field, OffsetDateTime value) {
    if (value.getSecond() != 0 || value.getNano() != 0 || value.getMinute() % 5 != 0) {
      throw CustomerInput.invalid(field, FieldErrorCode.INVALID_VALUE, "5분 단위여야 합니다.");
    }
  }

  private record Input(
      Long candidatePropertyId,
      Instant start,
      Instant end,
      int durationMinutes,
      Integer partySize,
      String requestNote) {}
}
