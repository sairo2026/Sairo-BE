package com.sairo.be.domain.ops.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/** 운영 홈·고객사·회원·운영자·감사 이력 응답이다. */
public final class OpsConsoleResponses {

  private OpsConsoleResponses() {}

  @Schema(description = "운영 홈 큐 요약")
  public record OpsHomeResponse(
      long pendingReviewCount,
      long overdueReviewCount,
      long unassignedInquiryCount,
      long paymentPendingCount,
      long refundFailedCount,
      long migrationReviewPendingCount,
      long privacyDeadlineApproachingCount,
      long deletionFailedCount,
      long activeIncidentCount,
      @Schema(description = "EXEC_VIEW 역할에만 포함한다") @JsonInclude(JsonInclude.Include.NON_NULL)
          ExecSummary execSummary) {}

  @Schema(description = "경영지표 요약")
  public record ExecSummary(
      long trialOfficeCount,
      long activeOfficeCount,
      long pastDueOfficeCount,
      long endedOfficeCount,
      long netCollectedAmount,
      OffsetDateTime asOf) {}

  @Schema(description = "고객사 목록 항목")
  public record OperatorOfficeItem(
      Long officeId,
      String name,
      String businessRegistrationNumber,
      String realEstateLicenseNumber,
      String registrationAuthority,
      String address,
      String registrationStatus,
      String subscriptionStatus,
      long approvedMembershipCount,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      long version) {}

  @Schema(description = "고객사 상세")
  public record OperatorOfficeDetailResponse(
      Long officeId,
      String name,
      String representativeName,
      String businessRegistrationNumber,
      String realEstateLicenseNumber,
      String phone,
      String address,
      OffsetDateTime createdAt,
      long approvedMembershipCount,
      String registrationAuthority,
      String registrationStatus,
      List<RegistrationHistoryItem> registrationHistory,
      List<OfficeMemberItem> members,
      EmploymentRosterSummary employmentRoster,
      SubscriptionSummary subscription,
      long openInquiryCount,
      ReturnGrantSummary returnGrant,
      ArchiveGrantSummary archiveGrant,
      OffsetDateTime updatedAt,
      long version) {}

  public record RegistrationHistoryItem(
      String fromStatus,
      String toStatus,
      String changedByType,
      String reason,
      OffsetDateTime createdAt) {}

  public record OfficeMemberItem(
      Long membershipId, String displayName, String role, String status) {}

  public record EmploymentRosterSummary(long agentCount, long deputyCount) {}

  public record SubscriptionSummary(
      String status,
      String plan,
      OffsetDateTime trialEndsAt,
      OffsetDateTime currentPeriodEnd,
      OffsetDateTime pastDueSince) {}

  public record ReturnGrantSummary(
      Long grantId, OffsetDateTime grantedAt, OffsetDateTime expiresAt, OffsetDateTime revokedAt) {}

  public record ArchiveGrantSummary(
      Long grantId,
      OffsetDateTime grantedAt,
      OffsetDateTime expiresAt,
      OffsetDateTime terminatedAt) {}

  @Schema(description = "회원 목록 항목")
  public record OperatorMemberItem(
      Long userId,
      String displayName,
      String accountStatus,
      String officeName,
      OffsetDateTime createdAt) {}

  @Schema(description = "회원 상세")
  public record OperatorMemberDetailResponse(
      Long userId,
      String displayName,
      String accountStatus,
      OffsetDateTime withdrawnAt,
      CurrentMembership currentMembership,
      PendingRequestSummary pendingRequestSummary,
      List<String> loginMethods,
      OffsetDateTime lastLoginAt,
      OffsetDateTime loginLockedUntil,
      List<MemberSession> sessions,
      List<SecurityEvent> securityEvents) {}

  public record CurrentMembership(Long officeId, String officeName, String role) {}

  public record PendingRequestSummary(String type, String status) {}

  public record MemberSession(
      String sessionId,
      OffsetDateTime createdAt,
      OffsetDateTime lastSeenAt,
      String deviceSummary) {}

  public record SecurityEvent(
      String eventType,
      OffsetDateTime occurredAt,
      String scope,
      String reason,
      OffsetDateTime reviewAt,
      String actorOperatorName) {}

  @Schema(description = "회원 긴급 접근 중단 결과")
  public record MemberEmergencySuspendResponse(
      Long userId, String scope, OffsetDateTime suspendedAt, OffsetDateTime reviewAt) {}

  @Schema(description = "운영자 계정과 활성 역할")
  public record OperatorItem(
      Long operatorId, String name, String status, List<String> roles, OffsetDateTime createdAt) {}

  @Schema(description = "담당 지정에 쓰는 활성 운영자 선택 목록")
  public record OpsOperatorOptionListResponse(List<OperatorOption> items) {}

  public record OperatorOption(Long operatorId, String name, List<String> roles) {}

  @Schema(description = "운영자 비활성화 결과")
  public record OperatorDisableResponse(
      Long operatorId, String status, int terminatedSessionCount, int invalidatedApprovalCount) {}

  @Schema(description = "운영자 재활성화 결과")
  public record OperatorEnableResponse(Long operatorId, String status) {}

  @Schema(description = "운영자 초대 생성 결과. 토큰 원문은 담지 않는다")
  public record OperatorInvitationCreateResponse(
      Long invitationId,
      String inviteeName,
      String inviteeEmail,
      List<String> roles,
      String status,
      OffsetDateTime expiresAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      long version) {}

  @Schema(description = "운영자 초대 목록 항목")
  public record OperatorInvitationItem(
      Long invitationId,
      String inviteeName,
      String inviteeEmail,
      List<String> roles,
      String status,
      OffsetDateTime expiresAt,
      Long invitedByOperatorId,
      String invitedByName,
      Long acceptedOperatorId,
      OffsetDateTime acceptedAt,
      OffsetDateTime cancelledAt,
      OffsetDateTime createdAt,
      long version) {}

  @Schema(description = "운영자 초대 취소 결과")
  public record OperatorInvitationCancelResponse(
      Long invitationId,
      String status,
      OffsetDateTime cancelledAt,
      OffsetDateTime updatedAt,
      long version) {}

  @Schema(description = "감사 이력 항목")
  public record OpsAuditLogItem(
      Long auditId,
      Long operatorAccountId,
      String operatorName,
      String actionType,
      String targetType,
      Long targetId,
      String detail,
      OffsetDateTime createdAt) {}
}
