package com.sairo.be.domain.visit.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

public final class ConfirmedScheduleResponses {

  private ConfirmedScheduleResponses() {}

  public record PublicConfirmedVisitResponse(
      String state,
      long scheduleVersion,
      String managerDisplayName,
      String managerLegalRole,
      String officeContactPhone,
      List<ConfirmedVisitItem> items) {}

  public record ConfirmedVisitItem(
      int sortOrder,
      OffsetDateTime scheduledStartAt,
      OffsetDateTime scheduledEndAt,
      String propertyAddress,
      @Schema(nullable = true) Integer travelDurationMinutes) {}

  public record ApproverConfirmedScheduleResponse(
      List<ApproverScheduleItem> items, String officeName, String officeContactPhone) {}

  public record ApproverScheduleItem(
      Long scheduleItemId,
      OffsetDateTime scheduledStartAt,
      OffsetDateTime scheduledEndAt,
      String result,
      String state,
      @Schema(nullable = true) Long confirmedRequestId,
      String applicantOfficeName,
      String applicantBrokerName,
      String applicantRelation) {}
}
