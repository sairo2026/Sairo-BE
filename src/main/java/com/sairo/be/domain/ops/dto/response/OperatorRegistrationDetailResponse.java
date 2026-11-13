package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "인증 신청 상세. 증빙 원문은 포함하지 않는다.")
public record OperatorRegistrationDetailResponse(
    Long registrationId,
    Long applicantUserId,
    String businessRegistrationNumber,
    String realEstateLicenseNumber,
    String requestedOfficeName,
    String requestedRepresentativeName,
    String requestedRepresentativePhone,
    String requestedAddress,
    String status,
    boolean hasEvidence,
    String entityType,
    String registrationAuthority,
    String applicantRegistrationRelation,
    int currentRevisionNo,
    List<Revision> revisions,
    List<ReviewChecklistItemResponse> reviewChecklist,
    boolean canApprove,
    String rejectionReason,
    List<History> statusHistory,
    @Schema(description = "승인으로 만들어진 사무소. 승인 전에는 null") Long officeId,
    @Schema(description = "심사 목표 기한. 심사 대기 상태가 아니면 null") OffsetDateTime reviewDueAt) {

  public record Revision(
      int revisionNo,
      OffsetDateTime submittedAt,
      String requestedOfficeName,
      String requestedAddress,
      List<String> evidenceTypes) {}

  public record History(
      Long historyId,
      String fromStatus,
      String toStatus,
      String changedByType,
      Long changedByOperatorId,
      String reason,
      OffsetDateTime createdAt) {}
}
