package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.service.CustomerReferenceService;
import com.sairo.be.domain.property.service.ManagingMemberDirectory;
import com.sairo.be.domain.property.service.PropertyReferenceService;
import com.sairo.be.domain.property.service.PropertyReferenceService.PropertySummary;
import com.sairo.be.domain.visit.dto.response.VisitCoordinationDetailResponse;
import com.sairo.be.domain.visit.dto.response.VisitCoordinationListResponse;
import com.sairo.be.domain.visit.entity.CoordinationStatus;
import com.sairo.be.domain.visit.entity.VisitCandidateProperty;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.entity.VisitCustomerLink;
import com.sairo.be.domain.visit.entity.VisitCustomerTimeSlot;
import com.sairo.be.domain.visit.mapper.VisitCoordinationMapper;
import com.sairo.be.domain.visit.repository.VisitCandidatePropertyRepository;
import com.sairo.be.domain.visit.repository.VisitCoordinationReadRepository;
import com.sairo.be.domain.visit.repository.VisitCustomerLinkRepository;
import com.sairo.be.domain.visit.repository.VisitCustomerTimeSlotRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.port.OfficeDirectory;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.PageQuery;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
@Transactional(readOnly = true)
public class VisitCoordinationQueryService {

  private final CoordinationAccess access;
  private final VisitCoordinationReadRepository readRepository;
  private final VisitCandidatePropertyRepository candidateRepository;
  private final VisitCustomerLinkRepository linkRepository;
  private final VisitCustomerTimeSlotRepository slotRepository;
  private final PropertyReferenceService propertyReferences;
  private final CustomerReferenceService customerReferences;
  private final ManagingMemberDirectory memberDirectory;
  private final OfficeDirectory officeDirectory;

  public VisitCoordinationQueryService(
      CoordinationAccess access,
      VisitCoordinationReadRepository readRepository,
      VisitCandidatePropertyRepository candidateRepository,
      VisitCustomerLinkRepository linkRepository,
      VisitCustomerTimeSlotRepository slotRepository,
      PropertyReferenceService propertyReferences,
      CustomerReferenceService customerReferences,
      ManagingMemberDirectory memberDirectory,
      OfficeDirectory officeDirectory) {
    this.access = access;
    this.readRepository = readRepository;
    this.candidateRepository = candidateRepository;
    this.linkRepository = linkRepository;
    this.slotRepository = slotRepository;
    this.propertyReferences = propertyReferences;
    this.customerReferences = customerReferences;
    this.memberDirectory = memberDirectory;
    this.officeDirectory = officeDirectory;
  }

  public VisitCoordinationListResponse list(
      StaffPrincipal principal, List<String> statusValues, PageQuery pageQuery) {
    List<CoordinationStatus> statuses = parseStatuses(statusValues);
    Long scope = access.managerScope(principal);
    long officeId = principal.officeId();
    List<VisitCoordinationReadRepository.ListRow> rows =
        readRepository.findPage(
            officeId,
            scope,
            statuses,
            pageQuery.size(),
            (long) pageQuery.page() * pageQuery.size());
    long total = readRepository.count(officeId, scope, statuses);
    Map<CoordinationStatus, Long> counts = readRepository.statusCounts(officeId, scope);

    List<Long> coordinationIds =
        rows.stream().map(VisitCoordinationReadRepository.ListRow::id).toList();
    Map<Long, List<VisitCandidateProperty>> candidatesByCoordination = new LinkedHashMap<>();
    Set<Long> propertyIds = new LinkedHashSet<>();
    if (!coordinationIds.isEmpty()) {
      for (VisitCandidateProperty candidate :
          candidateRepository.findByCoordinationIdInOrderBySortOrderAscIdAsc(coordinationIds)) {
        candidatesByCoordination
            .computeIfAbsent(candidate.getCoordinationId(), key -> new ArrayList<>())
            .add(candidate);
        propertyIds.add(candidate.getPropertyId());
      }
    }
    Map<Long, PropertySummary> properties = propertyReferences.findAll(propertyIds);
    Map<Long, String> customerNames =
        customerReferences.names(
            rows.stream().map(VisitCoordinationReadRepository.ListRow::customerId).toList());
    Map<Long, String> managerNames = managerNames(rows);
    return VisitCoordinationMapper.toList(
        rows,
        counts,
        candidatesByCoordination,
        properties,
        customerNames,
        managerNames,
        officeId,
        pageQuery,
        total);
  }

  public VisitCoordinationDetailResponse detail(
      StaffPrincipal principal, Long coordinationId, Long changeScheduleItemId) {
    VisitCoordination coordination = access.view(principal, coordinationId);
    if (changeScheduleItemId != null) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    List<VisitCandidateProperty> candidates =
        candidateRepository.findByCoordinationIdOrderBySortOrderAscIdAsc(coordinationId);
    Map<Long, PropertySummary> properties =
        propertyReferences.findAll(
            candidates.stream().map(VisitCandidateProperty::getPropertyId).toList());
    Map<Long, String> officeNames = new LinkedHashMap<>();
    for (VisitCandidateProperty candidate : candidates) {
      officeNames.computeIfAbsent(
          candidate.getPropertyOfficeId(),
          key -> officeDirectory.find(key).map(OfficeDirectory.OfficeSummary::name).orElse(""));
    }
    Optional<VisitCustomerLink> link =
        linkRepository.findFirstByCoordinationIdOrderByIdDesc(coordinationId);
    List<VisitCustomerTimeSlot> slots =
        link.filter(found -> found.getResponseVersion() > 0)
            .map(
                found ->
                    slotRepository.findByCustomerLinkIdAndResponseVersionOrderByStartsAtAscIdAsc(
                        found.getId(), found.getResponseVersion()))
            .orElse(List.of());
    String customerName =
        customerReferences
            .names(List.of(coordination.getCustomerId()))
            .getOrDefault(coordination.getCustomerId(), "");
    String managerName =
        memberDirectory
            .find(coordination.getManagerMembershipId())
            .map(ManagingMemberDirectory.ManagingMember::displayName)
            .orElse("");
    return VisitCoordinationMapper.toDetail(
        coordination,
        candidates,
        properties,
        officeNames,
        link.orElse(null),
        slots,
        customerName,
        managerName);
  }

  private Map<Long, String> managerNames(List<VisitCoordinationReadRepository.ListRow> rows) {
    Map<Long, String> names = new LinkedHashMap<>();
    for (VisitCoordinationReadRepository.ListRow row : rows) {
      names.computeIfAbsent(
          row.managerMembershipId(),
          key ->
              memberDirectory
                  .find(key)
                  .map(ManagingMemberDirectory.ManagingMember::displayName)
                  .orElse(""));
    }
    return names;
  }

  private static List<CoordinationStatus> parseStatuses(List<String> values) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    List<CoordinationStatus> statuses = new ArrayList<>();
    for (String value : values) {
      try {
        statuses.add(CoordinationStatus.valueOf(value));
      } catch (IllegalArgumentException e) {
        throw BusinessException.withFieldErrors(
            ErrorCode.INVALID_REQUEST,
            List.of(new FieldErrorItem("status", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 상태 값입니다.")));
      }
    }
    return statuses;
  }
}
