package com.sairo.be.domain.visit.dto.response;

import com.sairo.be.domain.visit.dto.response.ApproverConditionImpactPreviewResponse.AffectedItem;
import com.sairo.be.domain.visit.dto.response.ApproverVisitRequestCompareResponse.PrivacyNotice;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

public final class ApproverAccountResponses {

  private ApproverAccountResponses() {}

  public record ApproverOtpSendResponse(
      boolean accepted, int expiresInSeconds, int resendAfterSeconds) {}

  public record ApproverSessionResponse(
      Long profileId,
      String maskedPhone,
      int sessionIdleTimeoutMinutes,
      OffsetDateTime sessionExpiresAt,
      @Schema(nullable = true) OffsetDateTime reauthValidUntil,
      @Schema(nullable = true) Long claimedApprovalId,
      boolean profileCreated,
      int linkedApprovalCount,
      int pendingConfirmCount,
      PrivacyNotice privacyNotice) {}

  public record ApproverProfileResponse(
      Long profileId,
      String maskedPhone,
      OffsetDateTime createdAt,
      OffsetDateTime lastLoginAt,
      int linkedApprovalCount,
      int pendingConfirmCount) {}

  public record ApproverProfileCloseResponse(
      OffsetDateTime closedAt, int unlinkedApprovalCount, int endedSessionCount) {}

  public record ApproverPortalApprovalListResponse(
      List<ApproverPortalApprovalItem> items, PrivacyNotice privacyNotice) {}

  public record ApproverPortalApprovalItem(
      Long approvalId,
      String linkState,
      @Schema(nullable = true) String propertyDisplayName,
      @Schema(nullable = true) String propertyAddress,
      String propertyRegion,
      String approverType,
      String approvalRole,
      String ownerOfficeName,
      @Schema(nullable = true) String officeContactPhone,
      OffsetDateTime approvalValidFrom,
      OffsetDateTime approvalValidUntil,
      boolean approvalActive,
      @Schema(nullable = true) Integer activeConditionVersionNo,
      @Schema(nullable = true) OffsetDateTime conditionAppliesUntil,
      long pendingRequestCount,
      long upcomingConfirmedVisitCount,
      @Schema(nullable = true) OffsetDateTime nextConfirmedVisitAt) {}

  public record ApproverLinkDecisionResponse(Long approvalId, String linkState) {}

  public record ApproverConditionBulkPreviewResponse(List<BulkPreviewItem> items) {}

  public record BulkPreviewItem(
      Long approvalId,
      @Schema(nullable = true) String propertyDisplayName,
      boolean eligible,
      @Schema(nullable = true) String ineligibleReason,
      boolean hasImpact,
      List<AffectedItem> affectedItems) {}

  public record ApproverConditionBulkApplyResponse(
      Long batchId,
      int requestedCount,
      int appliedCount,
      int needsImpactAckCount,
      int failedCount,
      List<BulkApplyItem> items) {}

  public record BulkApplyItem(
      Long approvalId,
      String result,
      @Schema(nullable = true) Long conditionId,
      @Schema(nullable = true) Integer versionNo,
      @Schema(nullable = true) String failureCode,
      int affectedItemCount) {}
}
