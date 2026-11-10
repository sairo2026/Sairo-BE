package com.sairo.be.domain.visit.mapper;

import com.sairo.be.domain.property.service.PropertyReferenceService.PropertySummary;
import com.sairo.be.domain.visit.dto.response.VisitCoordinationDetailResponse;
import com.sairo.be.domain.visit.dto.response.VisitCoordinationListResponse;
import com.sairo.be.domain.visit.entity.CoordinationStatus;
import com.sairo.be.domain.visit.entity.VisitCandidateProperty;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.entity.VisitCustomerLink;
import com.sairo.be.domain.visit.entity.VisitCustomerTimeSlot;
import com.sairo.be.domain.visit.repository.VisitCoordinationReadRepository.ListRow;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.TimeRules;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class VisitCoordinationMapper {

  private VisitCoordinationMapper() {}

  public static VisitCoordinationListResponse toList(
      List<ListRow> rows,
      Map<CoordinationStatus, Long> counts,
      Map<Long, List<VisitCandidateProperty>> candidatesByCoordination,
      Map<Long, PropertySummary> properties,
      Map<Long, String> customerNames,
      Map<Long, String> managerNames,
      long officeId,
      PageQuery pageQuery,
      long total) {
    Map<String, Long> statusCounts = new LinkedHashMap<>();
    for (CoordinationStatus status : CoordinationStatus.values()) {
      statusCounts.put(status.name(), counts.getOrDefault(status, 0L));
    }
    List<VisitCoordinationListResponse.Item> items =
        rows.stream()
            .map(
                row -> {
                  List<VisitCandidateProperty> candidates =
                      candidatesByCoordination.getOrDefault(row.id(), List.of());
                  return new VisitCoordinationListResponse.Item(
                      row.id(),
                      customerNames.getOrDefault(row.customerId(), ""),
                      candidates.size(),
                      managerNames.getOrDefault(row.managerMembershipId(), ""),
                      row.periodStartDate(),
                      row.periodEndDate(),
                      candidates.stream()
                          .map(
                              candidate ->
                                  new VisitCoordinationListResponse.Candidate(
                                      candidate.getId(),
                                      propertyName(properties, candidate.getPropertyId())))
                          .toList(),
                      row.status().name(),
                      row.managerMembershipId(),
                      TimeRules.toDisplayOffset(row.createdAt()),
                      officeId,
                      TimeRules.toDisplayOffset(row.updatedAt()),
                      row.version());
                })
            .toList();
    return new VisitCoordinationListResponse(
        statusCounts, items, pageQuery.page(), pageQuery.size(), total);
  }

  public static VisitCoordinationDetailResponse toDetail(
      VisitCoordination coordination,
      List<VisitCandidateProperty> candidates,
      Map<Long, PropertySummary> properties,
      Map<Long, String> officeNames,
      VisitCustomerLink link,
      List<VisitCustomerTimeSlot> slots,
      String customerName,
      String managerName) {
    return new VisitCoordinationDetailResponse(
        coordination.getId(),
        coordination.getStatus().name(),
        coordination.getCustomerId(),
        customerName,
        coordination.getManagerMembershipId(),
        managerName,
        null,
        link == null ? null : customerLink(link),
        rescheduleAlert(coordination),
        coordination.getPeriodStartDate(),
        coordination.getPeriodEndDate(),
        link == null ? null : TimeRules.toDisplayOffset(link.getRespondedAt()),
        slots.stream()
            .map(
                slot ->
                    new VisitCoordinationDetailResponse.TimeSlot(
                        TimeRules.toDisplayOffset(slot.getStartsAt()),
                        TimeRules.toDisplayOffset(slot.getEndsAt())))
            .toList(),
        candidates.stream()
            .map(
                candidate ->
                    candidate(
                        candidate,
                        properties.get(candidate.getPropertyId()),
                        officeNames.getOrDefault(candidate.getPropertyOfficeId(), "")))
            .toList(),
        List.of(),
        List.of(),
        TimeRules.toDisplayOffset(coordination.getCreatedAt()),
        TimeRules.toDisplayOffset(coordination.getUpdatedAt()),
        coordination.getVersion(),
        coordination.getOfficeId());
  }

  private static VisitCoordinationDetailResponse.CustomerLink customerLink(VisitCustomerLink link) {
    return new VisitCoordinationDetailResponse.CustomerLink(
        link.getId(),
        TimeRules.toDisplayOffset(link.getIssuedAt()),
        TimeRules.toDisplayOffset(link.getExpiresAt()),
        TimeRules.toDisplayOffset(link.getRevokedAt()),
        link.getResponseVersion());
  }

  private static VisitCoordinationDetailResponse.RescheduleAlert rescheduleAlert(
      VisitCoordination coordination) {
    if (coordination.getStatus() != CoordinationStatus.RESCHEDULE_REQUIRED) {
      return null;
    }
    return new VisitCoordinationDetailResponse.RescheduleAlert(
        coordination.getRescheduleTriggerType().name(),
        coordination.getRescheduleReason(),
        TimeRules.toDisplayOffset(coordination.getRescheduleRaisedAt()),
        coordination.getRescheduleApprovalId());
  }

  private static VisitCoordinationDetailResponse.Candidate candidate(
      VisitCandidateProperty candidate, PropertySummary property, String ownerOfficeName) {
    boolean crossOffice = candidate.isCrossOffice();
    return new VisitCoordinationDetailResponse.Candidate(
        candidate.getId(),
        candidate.getPropertyId(),
        candidate.getSortOrder(),
        property == null ? "" : property.name(),
        property == null ? null : property.address(),
        candidate.getPropertyOfficeId(),
        ownerOfficeName,
        crossOffice,
        null,
        null,
        crossOffice || property == null ? null : property.addressDetail(),
        null,
        List.of(),
        List.of());
  }

  private static String propertyName(Map<Long, PropertySummary> properties, Long propertyId) {
    PropertySummary property = properties.get(propertyId);
    return property == null ? "" : property.name();
  }
}
