package com.sairo.be.domain.visit.dto.response;

import com.sairo.be.domain.visit.dto.response.ApproverVisitRequestCompareResponse.PrivacyNotice;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

public final class VisitReviewResponses {

  private VisitReviewResponses() {}

  public record ReviewAnswers(String punctuality, String advanceContact, String asRequested) {}

  public record ApproverVisitReviewListResponse(
      String propertyAddress,
      int reviewWindowDays,
      List<ApproverReviewItem> items,
      PrivacyNotice privacyNotice) {}

  public record ApproverReviewItem(
      Long scheduleItemId,
      OffsetDateTime scheduledStartAt,
      OffsetDateTime scheduledEndAt,
      OffsetDateTime actualVisitedAt,
      String applicantOfficeName,
      String applicantBrokerName,
      OffsetDateTime reviewDeadlineAt,
      String reviewStatus,
      boolean editable,
      int revisionCount,
      int revisionsRemaining,
      @Schema(nullable = true) Long version,
      @Schema(nullable = true) ReviewAnswers answers,
      boolean isPreferredBroker) {}

  public record ApproverVisitReviewResponse(
      Long scheduleItemId,
      String reviewStatus,
      @Schema(nullable = true) ReviewAnswers answers,
      int revisionCount,
      int revisionsRemaining,
      OffsetDateTime reviewDeadlineAt,
      OffsetDateTime submittedAt,
      OffsetDateTime lastRevisedAt,
      long version) {}

  public record ApproverPreferredBrokerListResponse(
      int maxActiveCount,
      List<PreferredBrokerItem> items,
      List<PreferredBrokerCandidate> candidates,
      PrivacyNotice privacyNotice) {}

  public record PreferredBrokerItem(
      Long preferredBrokerId,
      String applicantOfficeName,
      String applicantBrokerName,
      OffsetDateTime designatedAt) {}

  public record PreferredBrokerCandidate(
      @Schema(nullable = true) Long sourceRequestId,
      @Schema(nullable = true) Long sourceScheduleItemId,
      String applicantOfficeName,
      String applicantBrokerName) {}

  public record ApproverPreferredBrokerResponse(
      Long preferredBrokerId,
      String applicantOfficeName,
      String applicantBrokerName,
      OffsetDateTime designatedAt,
      @Schema(nullable = true) OffsetDateTime releasedAt) {}

  public record BrokerVisitTrustSummaryResponse(
      String policyVersion,
      OffsetDateTime asOf,
      int windowMonths,
      String state,
      @Schema(nullable = true) String overallBand,
      int completedVisitCount,
      int evaluatedVisitCount,
      int distinctApproverCount,
      int excludedReviewCount,
      List<OwnTrustIndicator> indicators,
      List<String> unconfirmedIndicators) {}

  public record OwnTrustIndicator(
      String indicator, int answeredCount, int yesCount, @Schema(nullable = true) String band) {}

  public record ReceivedReviewItem(
      Long reviewId,
      Long scheduleItemId,
      OffsetDateTime actualVisitedAt,
      String propertyName,
      String reviewedBrokerName,
      String reviewStatus,
      ReviewAnswers answers,
      OffsetDateTime submittedAt,
      OffsetDateTime lastRevisedAt,
      boolean canDispute,
      @Schema(nullable = true) ReceivedDispute dispute) {}

  public record ReceivedDispute(
      Long disputeId,
      String status,
      OffsetDateTime filedAt,
      @Schema(nullable = true) OffsetDateTime decidedAt,
      @Schema(nullable = true) String decisionNote) {}

  public record VisitReviewDisputeResponse(
      Long disputeId,
      Long reviewId,
      String status,
      String reasonCode,
      OffsetDateTime filedAt,
      OffsetDateTime targetDecisionAt,
      String reviewStatus) {}

  public record OpsDisputeItem(
      Long disputeId,
      String status,
      String reasonCode,
      String reasonText,
      OffsetDateTime filedAt,
      OffsetDateTime dueAt,
      OpsDisputedReview review,
      OpsReviewedBroker reviewedBroker,
      @Schema(nullable = true) OffsetDateTime decidedAt,
      @Schema(nullable = true) String decisionNote) {}

  public record OpsDisputedReview(
      Long reviewId,
      Long scheduleItemId,
      String reviewStatus,
      OffsetDateTime actualVisitedAt,
      ReviewAnswers answers,
      OffsetDateTime submittedAt,
      OffsetDateTime lastRevisedAt,
      int revisionCount,
      String approverType,
      boolean issuerIsReviewedBroker) {}

  public record OpsReviewedBroker(
      Long membershipId, String brokerName, Long officeId, String officeName) {}

  public record OpsVisitReviewResponse(
      Long reviewId,
      String reviewStatus,
      @Schema(nullable = true) Long disputeId,
      @Schema(nullable = true) String disputeStatus,
      @Schema(nullable = true) OffsetDateTime removedAt,
      OffsetDateTime decidedAt) {}
}
