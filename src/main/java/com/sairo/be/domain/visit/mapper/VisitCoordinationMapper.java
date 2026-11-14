package com.sairo.be.domain.visit.mapper;

import com.sairo.be.domain.property.service.PropertyReferenceService.PropertySummary;
import com.sairo.be.domain.visit.dto.response.VisitCoordinationDetailResponse;
import com.sairo.be.domain.visit.dto.response.VisitCoordinationListResponse;
import com.sairo.be.domain.visit.entity.CoordinationStatus;
import com.sairo.be.domain.visit.entity.VisitCandidateProperty;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.entity.VisitCustomerLink;
import com.sairo.be.domain.visit.entity.VisitCustomerTimeSlot;
import com.sairo.be.domain.visit.repository.ScheduleItemRepository.ItemRow;
import com.sairo.be.domain.visit.repository.VisitCoordinationReadRepository.ListRow;
import com.sairo.be.domain.visit.service.ScheduleStateService;
import com.sairo.be.domain.visit.service.ScheduleStateService.ItemState;
import com.sairo.be.domain.visit.service.VisitRequestChecker.Slot;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.TimeRules;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class VisitCoordinationMapper {

  /** 다른 사무소 후보 매물의 신청 허용 표시 값이다. */
  public record CandidateGrant(Long grantId, String state) {}

  private static final String PASSED = "PASSED";

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
      String managerName,
      List<ItemRow> items,
      long scheduleVersion,
      Map<Long, List<Slot>> applicableSlots,
      Map<Long, ItemState> states,
      List<ScheduleStateService.Alternative> alternatives,
      Map<Long, CandidateGrant> grants) {
    java.util.Set<Long> confirmedCandidates = new java.util.HashSet<>();
    for (ItemRow item : items) {
      if (item.confirmedRequestId() != null && item.kept()) {
        confirmedCandidates.add(item.candidatePropertyId());
      }
    }
    return new VisitCoordinationDetailResponse(
        coordination.getId(),
        coordination.getStatus().name(),
        coordination.getCustomerId(),
        customerName,
        coordination.getManagerMembershipId(),
        managerName,
        scheduleVersion > 0 ? scheduleVersion : null,
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
                        officeNames.getOrDefault(candidate.getPropertyOfficeId(), ""),
                        grants.get(candidate.getId()),
                        confirmedCandidates.contains(candidate.getId()),
                        applicableSlots.getOrDefault(candidate.getId(), List.of())))
            .toList(),
        scheduleItems(items, states),
        alternatives(alternatives),
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
      VisitCandidateProperty candidate,
      PropertySummary property,
      String ownerOfficeName,
      CandidateGrant grant,
      boolean confirmed,
      List<Slot> applicable) {
    boolean crossOffice = candidate.isCrossOffice();
    boolean hideDetail = crossOffice && !confirmed;
    return new VisitCoordinationDetailResponse.Candidate(
        candidate.getId(),
        candidate.getPropertyId(),
        candidate.getSortOrder(),
        property == null ? "" : property.name(),
        property == null ? null : property.address(),
        candidate.getPropertyOfficeId(),
        ownerOfficeName,
        crossOffice,
        crossOffice && grant != null ? grant.grantId() : null,
        crossOffice && grant != null ? grant.state() : null,
        hideDetail || property == null ? null : property.addressDetail(),
        null,
        List.of(),
        applicable.stream()
            .map(
                slot ->
                    new VisitCoordinationDetailResponse.TimeSlot(
                        TimeRules.toDisplayOffset(slot.startsAt()),
                        TimeRules.toDisplayOffset(slot.endsAt())))
            .toList());
  }

  public static List<VisitCoordinationDetailResponse.Alternative> alternatives(
      List<ScheduleStateService.Alternative> alternatives) {
    VisitCoordinationDetailResponse.Checks passed =
        new VisitCoordinationDetailResponse.Checks(PASSED, PASSED, PASSED, PASSED, PASSED);
    return alternatives.stream()
        .map(
            alternative ->
                new VisitCoordinationDetailResponse.Alternative(
                    alternative.scheduleItemId(),
                    alternative.candidatePropertyId(),
                    TimeRules.toDisplayOffset(alternative.startAt()),
                    TimeRules.toDisplayOffset(alternative.endAt()),
                    passed))
        .toList();
  }

  private static List<VisitCoordinationDetailResponse.ScheduleItem> scheduleItems(
      List<ItemRow> items, Map<Long, ItemState> states) {
    List<ItemRow> kept = items.stream().filter(ItemRow::kept).toList();
    List<VisitCoordinationDetailResponse.ScheduleItem> result = new java.util.ArrayList<>();
    for (ItemRow item : items) {
      Boolean feasible = null;
      if (item.kept() && item.travelEndsAt() != null) {
        int index = kept.indexOf(item);
        if (index >= 0 && index + 1 < kept.size()) {
          feasible = !item.travelEndsAt().isAfter(kept.get(index + 1).startAt());
        }
      }
      ItemState state = states.get(item.id());
      result.add(
          new VisitCoordinationDetailResponse.ScheduleItem(
              item.id(),
              item.candidatePropertyId(),
              TimeRules.toDisplayOffset(item.startAt()),
              TimeRules.toDisplayOffset(item.endAt()),
              item.result(),
              TimeRules.toDisplayOffset(item.resultRecordedAt()),
              item.correctionCount(),
              state == null ? ScheduleStateService.VALID : state.state(),
              feasible));
    }
    return result;
  }

  private static String propertyName(Map<Long, PropertySummary> properties, Long propertyId) {
    PropertySummary property = properties.get(propertyId);
    return property == null ? "" : property.name();
  }
}
