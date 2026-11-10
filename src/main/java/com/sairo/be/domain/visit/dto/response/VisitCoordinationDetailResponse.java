package com.sairo.be.domain.visit.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record VisitCoordinationDetailResponse(
    Long coordinationId,
    String status,
    Long customerId,
    String customerName,
    Long managerMembershipId,
    String managerName,
    @Schema(nullable = true) Long confirmedScheduleVersion,
    @Schema(nullable = true) CustomerLink customerLink,
    @Schema(nullable = true) RescheduleAlert rescheduleAlert,
    LocalDate periodStartDate,
    LocalDate periodEndDate,
    @Schema(nullable = true) OffsetDateTime customerRespondedAt,
    List<TimeSlot> customerTimeSlots,
    List<Candidate> candidateProperties,
    List<ScheduleItem> scheduleItems,
    List<Alternative> alternatives,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    Long version,
    Long officeId) {

  public record CustomerLink(
      Long linkId,
      OffsetDateTime issuedAt,
      OffsetDateTime expiresAt,
      @Schema(nullable = true) OffsetDateTime revokedAt,
      int responseVersion) {}

  public record RescheduleAlert(
      String triggerType,
      @Schema(nullable = true) String reason,
      OffsetDateTime raisedAt,
      @Schema(nullable = true) Long approvalId) {}

  public record TimeSlot(OffsetDateTime startsAt, OffsetDateTime endsAt) {}

  public record Candidate(
      Long candidatePropertyId,
      Long propertyId,
      int sortOrder,
      String propertyName,
      @Schema(nullable = true) String propertyAddress,
      Long ownerOfficeId,
      String ownerOfficeName,
      boolean crossOffice,
      @Schema(nullable = true) Long grantId,
      @Schema(nullable = true) String grantState,
      @Schema(nullable = true) String propertyAddressDetail,
      @Schema(nullable = true) OffsetDateTime approverRespondedAt,
      List<TimeSlot> approverTimeSlots,
      List<TimeSlot> applicableSlots) {}

  public record ScheduleItem(
      Long itemId,
      Long candidatePropertyId,
      @Schema(nullable = true) OffsetDateTime scheduledStartAt,
      @Schema(nullable = true) OffsetDateTime scheduledEndAt,
      String result,
      @Schema(nullable = true) OffsetDateTime resultRecordedAt,
      int correctionCount,
      String state,
      @Schema(nullable = true) Boolean travelFeasibleToNext) {}

  public record Alternative(
      Long scheduleItemId,
      Long candidatePropertyId,
      OffsetDateTime startAt,
      OffsetDateTime endAt,
      Checks checks) {}

  public record Checks(
      String customer,
      String broker,
      String approverCondition,
      String confirmedVisits,
      String travel) {}
}
