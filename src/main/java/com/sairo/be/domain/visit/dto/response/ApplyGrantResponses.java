package com.sairo.be.domain.visit.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

public final class ApplyGrantResponses {

  private ApplyGrantResponses() {}

  public record ApplyGrantResponse(
      Long grantId,
      Long approvalId,
      Long propertyId,
      Long ownerOfficeId,
      Long granteeOfficeId,
      String granteeOfficeName,
      @Schema(nullable = true) Long granteeMembershipId,
      @Schema(nullable = true) String granteeMemberName,
      String scope,
      Long grantedByMembershipId,
      String grantedByName,
      OffsetDateTime grantedAt,
      @Schema(nullable = true) OffsetDateTime expiresAt,
      @Schema(nullable = true) OffsetDateTime revokedAt,
      @Schema(nullable = true) String revokeSource,
      @Schema(nullable = true) String revokeReason,
      String state,
      int openRequestCount) {}

  public record ApplyGrantRevokeResponse(
      ApplyGrantResponse grant, List<Long> closedRequestIds, List<Long> keptScheduleItemIds) {}

  public record ReceivedApplyGrantItem(
      Long grantId,
      Long approvalId,
      Long propertyId,
      Long ownerOfficeId,
      String ownerOfficeName,
      String scope,
      @Schema(nullable = true) Long granteeMembershipId,
      String propertyName,
      String propertyAddress,
      String categoryName,
      List<String> dealTypes,
      OffsetDateTime approvalValidUntil,
      @Schema(nullable = true) OffsetDateTime grantExpiresAt,
      boolean conditionRegistered,
      @Schema(nullable = true) OffsetDateTime conditionAppliesUntil) {}

  public record CrossOfficeRequestItem(
      Long requestId,
      String requestKind,
      String status,
      String applicantOfficeName,
      String applicantBrokerName,
      OffsetDateTime requestedStartAt,
      OffsetDateTime requestedEndAt,
      int expectedDurationMinutes,
      OffsetDateTime submittedAt,
      @Schema(nullable = true) OffsetDateTime closedAt,
      @Schema(nullable = true) String closeReasonCode) {}

  public record CrossOfficeScheduleItem(
      Long scheduleItemId,
      OffsetDateTime scheduledStartAt,
      OffsetDateTime scheduledEndAt,
      String result,
      String state,
      String applicantOfficeName,
      String applicantBrokerName,
      Long confirmedRequestId) {}
}
