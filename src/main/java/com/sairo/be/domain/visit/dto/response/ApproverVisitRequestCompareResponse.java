package com.sairo.be.domain.visit.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

public record ApproverVisitRequestCompareResponse(
    String propertyAddress,
    @Schema(nullable = true) Integer activeConditionVersionNo,
    String approvalRole,
    List<String> grantedOfficeNames,
    String trustPolicyVersion,
    OffsetDateTime trustAsOf,
    List<String> unconfirmedIndicators,
    List<Item> items,
    PrivacyNotice privacyNotice) {

  public record Item(
      Long requestId,
      String requestKind,
      String status,
      long version,
      String applicantOfficeName,
      String applicantBrokerName,
      String applicantRelation,
      OffsetDateTime requestedStartAt,
      OffsetDateTime requestedEndAt,
      int expectedDurationMinutes,
      @Schema(nullable = true) Integer partySize,
      @Schema(nullable = true) String requestNote,
      OffsetDateTime submittedAt,
      boolean isPreferredBroker,
      ApplicantHistory applicantHistory,
      @Schema(nullable = true) ApplicantTrust applicantTrust) {}

  public record ApplicantHistory(
      long previousCompletedVisitCount, long previousCancelledVisitCount) {}

  public record ApplicantTrust(
      String state,
      @Schema(nullable = true) String overallBand,
      int completedVisitCount,
      int evaluatedVisitCount,
      @Schema(nullable = true) Integer distinctApproverCount,
      List<TrustIndicator> indicators) {}

  public record TrustIndicator(
      String indicator,
      @Schema(nullable = true) Integer answeredCount,
      @Schema(nullable = true) Integer yesCount,
      @Schema(nullable = true) String band) {}

  public record PrivacyNotice(String noticeCode, String noticeVersion, int retentionDays) {}
}
