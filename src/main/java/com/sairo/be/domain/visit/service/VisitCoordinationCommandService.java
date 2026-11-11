package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.customer.service.CustomerReferenceService;
import com.sairo.be.domain.customer.service.CustomerReferenceService.CustomerSummary;
import com.sairo.be.domain.office.service.DeputyQuotaService;
import com.sairo.be.domain.property.service.ManagingMemberDirectory;
import com.sairo.be.domain.property.service.PropertyReferenceService.PropertySummary;
import com.sairo.be.domain.visit.dto.response.ScheduleOutcomeResponses;
import com.sairo.be.domain.visit.dto.response.ScheduleOutcomeResponses.VisitCoordinationCloneResponse;
import com.sairo.be.domain.visit.dto.response.VisitCoordinationCreateResponse;
import com.sairo.be.domain.visit.entity.CoordinationStatus;
import com.sairo.be.domain.visit.entity.StatusChangeSource;
import com.sairo.be.domain.visit.entity.VisitCandidateProperty;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.repository.VisitCandidatePropertyRepository;
import com.sairo.be.domain.visit.repository.VisitCoordinationRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StaffRole;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@Service
public class VisitCoordinationCommandService {

  static final int MAX_PERIOD_DAYS = 30;

  private static final Set<String> CREATE_KEYS =
      Set.of(
          "customerId",
          "candidatePropertyIds",
          "managerMembershipId",
          "periodStartDate",
          "periodEndDate");
  private static final Set<String> CLONE_KEYS = Set.of("periodStartDate", "periodEndDate");

  private final VisitCoordinationRepository coordinationRepository;
  private final VisitCandidatePropertyRepository candidateRepository;
  private final CoordinationStatusService statusService;
  private final CandidateAdmission candidateAdmission;
  private final CustomerReferenceService customerReferences;
  private final ManagingMemberDirectory memberDirectory;
  private final DeputyQuotaService deputyQuotaService;
  private final CoordinationAccess access;
  private final Clock clock;

  public VisitCoordinationCommandService(
      VisitCoordinationRepository coordinationRepository,
      VisitCandidatePropertyRepository candidateRepository,
      CoordinationStatusService statusService,
      CandidateAdmission candidateAdmission,
      CustomerReferenceService customerReferences,
      ManagingMemberDirectory memberDirectory,
      DeputyQuotaService deputyQuotaService,
      CoordinationAccess access,
      Clock clock) {
    this.coordinationRepository = coordinationRepository;
    this.candidateRepository = candidateRepository;
    this.statusService = statusService;
    this.candidateAdmission = candidateAdmission;
    this.customerReferences = customerReferences;
    this.memberDirectory = memberDirectory;
    this.deputyQuotaService = deputyQuotaService;
    this.access = access;
    this.clock = clock;
  }

  @Transactional
  public VisitCoordinationCreateResponse create(StaffPrincipal principal, JsonNode body) {
    CustomerInput.requireObject(body, CREATE_KEYS);
    Long customerId = CustomerInput.requiredLong(body, "customerId");
    List<Long> propertyIds = propertyIds(body);
    Long requestedManager = CustomerInput.optionalLong(body, "managerMembershipId");
    LocalDate start = CustomerInput.requiredDate(body, "periodStartDate");
    LocalDate end = CustomerInput.requiredDate(body, "periodEndDate");
    LocalDate today = TimeRules.displayDate(clock.instant());
    if (start.isBefore(today)) {
      throw CustomerInput.invalid("periodStartDate", FieldErrorCode.OUT_OF_RANGE, "오늘 이후여야 합니다.");
    }
    if (end.isBefore(start)) {
      throw CustomerInput.invalid("periodEndDate", FieldErrorCode.OUT_OF_RANGE, "시작일 이후여야 합니다.");
    }
    Long managerId = requestedManager == null ? principal.membershipId() : requestedManager;
    requireDeputyAllowed(principal, managerId);
    if (end.isAfter(today.plusDays(MAX_PERIOD_DAYS))) {
      throw new BusinessException(ErrorCode.PERIOD_TOO_LONG);
    }
    if (propertyIds.size() > VisitCandidateProperty.MAX_PER_COORDINATION) {
      throw new BusinessException(ErrorCode.CANDIDATE_LIMIT_EXCEEDED);
    }
    CustomerSummary customer = customerReferences.resolveFinal(principal.officeId(), customerId);
    if (customer.archived()) {
      throw new BusinessException(ErrorCode.CUSTOMER_ARCHIVED);
    }
    requireAssignableManager(principal.officeId(), managerId);
    List<PropertySummary> properties = new ArrayList<>();
    for (Long propertyId : propertyIds) {
      properties.add(candidateAdmission.admit(principal, propertyId));
    }

    VisitCoordination coordination =
        coordinationRepository.saveAndFlush(
            VisitCoordination.open(
                principal.officeId(), customer.customerId(), managerId, start, end));
    statusService.recordOpening(coordination, StatusChangeSource.STAFF, principal.membershipId());
    List<VisitCoordinationCreateResponse.Candidate> saved = new ArrayList<>();
    int order = 1;
    for (PropertySummary property : properties) {
      VisitCandidateProperty candidate =
          candidateRepository.save(
              VisitCandidateProperty.of(
                  coordination.getId(),
                  principal.officeId(),
                  property.propertyId(),
                  property.officeId(),
                  order));
      saved.add(
          new VisitCoordinationCreateResponse.Candidate(
              candidate.getId(), candidate.getPropertyId(), candidate.getSortOrder()));
      order++;
    }
    candidateRepository.flush();
    return new VisitCoordinationCreateResponse(
        coordination.getId(),
        coordination.getStatus().name(),
        saved,
        coordination.getOfficeId(),
        TimeRules.toDisplayOffset(coordination.getCreatedAt()),
        TimeRules.toDisplayOffset(coordination.getUpdatedAt()),
        coordination.getVersion());
  }

  /** 취소된 조율 건의 고객·담당자·후보 매물로 새 기간의 조율 건을 만든다. 링크와 출입승인 응답은 복제하지 않는다. */
  @Transactional
  public VisitCoordinationCloneResponse cloneCancelled(
      StaffPrincipal principal, Long coordinationId, JsonNode body) {
    CustomerInput.requireObject(body, CLONE_KEYS);
    LocalDate start = CustomerInput.requiredDate(body, "periodStartDate");
    LocalDate end = CustomerInput.requiredDate(body, "periodEndDate");
    VisitCoordination source = access.view(principal, coordinationId);
    if (source.getStatus() != CoordinationStatus.CANCELLED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    LocalDate today = TimeRules.displayDate(clock.instant());
    if (start.isBefore(today)) {
      throw CustomerInput.invalid("periodStartDate", FieldErrorCode.OUT_OF_RANGE, "오늘 이후여야 합니다.");
    }
    if (end.isBefore(start)) {
      throw CustomerInput.invalid("periodEndDate", FieldErrorCode.OUT_OF_RANGE, "시작일 이후여야 합니다.");
    }
    if (end.isAfter(today.plusDays(MAX_PERIOD_DAYS))) {
      throw new BusinessException(ErrorCode.PERIOD_TOO_LONG);
    }
    Long managerId = source.getManagerMembershipId();
    requireDeputyAllowed(principal, managerId);
    CustomerSummary customer =
        customerReferences.resolveFinal(principal.officeId(), source.getCustomerId());
    if (customer.archived()) {
      throw new BusinessException(ErrorCode.CUSTOMER_ARCHIVED);
    }
    requireAssignableManager(principal.officeId(), managerId);
    List<VisitCandidateProperty> sourceCandidates =
        candidateRepository.findByCoordinationIdOrderBySortOrderAscIdAsc(coordinationId);
    List<PropertySummary> properties = new ArrayList<>();
    for (VisitCandidateProperty candidate : sourceCandidates) {
      properties.add(candidateAdmission.admit(principal, candidate.getPropertyId()));
    }
    VisitCoordination coordination =
        coordinationRepository.saveAndFlush(
            VisitCoordination.open(
                principal.officeId(), customer.customerId(), managerId, start, end));
    statusService.recordOpening(coordination, StatusChangeSource.STAFF, principal.membershipId());
    List<ScheduleOutcomeResponses.CandidateRef> saved = new ArrayList<>();
    for (int index = 0; index < properties.size(); index++) {
      VisitCandidateProperty origin = sourceCandidates.get(index);
      VisitCandidateProperty candidate =
          VisitCandidateProperty.of(
              coordination.getId(),
              principal.officeId(),
              properties.get(index).propertyId(),
              properties.get(index).officeId(),
              origin.getSortOrder());
      candidate.arrange(
          origin.getSortOrder(),
          origin.getVisitDurationMinutes(),
          origin.getTravelDurationMinutes());
      candidate = candidateRepository.save(candidate);
      saved.add(
          new ScheduleOutcomeResponses.CandidateRef(
              candidate.getId(), candidate.getPropertyId(), candidate.getSortOrder()));
    }
    candidateRepository.flush();
    return new VisitCoordinationCloneResponse(
        coordination.getId(),
        coordination.getStatus().name(),
        saved,
        coordination.getOfficeId(),
        TimeRules.toDisplayOffset(coordination.getCreatedAt()),
        TimeRules.toDisplayOffset(coordination.getUpdatedAt()),
        coordination.getVersion());
  }

  private void requireDeputyAllowed(StaffPrincipal principal, Long managerId) {
    if (principal.role() != StaffRole.DEPUTY) {
      return;
    }
    if (!principal.membershipId().equals(managerId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    if (deputyQuotaService.current(principal.officeId()).exceeded()) {
      throw new BusinessException(ErrorCode.DEPUTY_VISIT_LOCKED);
    }
  }

  private void requireAssignableManager(Long officeId, Long managerId) {
    ManagingMemberDirectory.ManagingMember member =
        memberDirectory
            .find(managerId)
            .filter(found -> found.officeId().equals(officeId))
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!"APPROVED".equals(member.status())) {
      throw CustomerInput.invalid(
          "managerMembershipId", FieldErrorCode.NOT_ALLOWED, "승인된 소속만 담당자로 지정할 수 있습니다.");
    }
  }

  private static List<Long> propertyIds(JsonNode body) {
    if (CustomerInput.isNull(body, "candidatePropertyIds")) {
      throw CustomerInput.invalid("candidatePropertyIds", FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    JsonNode node = body.get("candidatePropertyIds");
    if (!node.isArray()) {
      throw CustomerInput.invalid(
          "candidatePropertyIds", FieldErrorCode.TYPE_MISMATCH, "배열이어야 합니다.");
    }
    if (node.isEmpty()) {
      throw CustomerInput.invalid(
          "candidatePropertyIds", FieldErrorCode.REQUIRED, "후보 매물은 1개 이상이어야 합니다.");
    }
    List<Long> ids = new ArrayList<>();
    Set<Long> seen = new HashSet<>();
    for (JsonNode element : node) {
      if (!element.isIntegralNumber() || !element.canConvertToLong()) {
        throw CustomerInput.invalid(
            "candidatePropertyIds", FieldErrorCode.TYPE_MISMATCH, "정수 배열이어야 합니다.");
      }
      if (!seen.add(element.longValue())) {
        throw CustomerInput.invalid(
            "candidatePropertyIds", FieldErrorCode.INVALID_VALUE, "중복된 매물이 있습니다.");
      }
      ids.add(element.longValue());
    }
    return ids;
  }
}
