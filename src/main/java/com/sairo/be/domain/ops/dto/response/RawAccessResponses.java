package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/** 원문 접근 승인·실행 응답이다. */
public final class RawAccessResponses {

  private RawAccessResponses() {}

  @Schema(description = "원문 접근 요청 생성 결과")
  public record RawAccessCreateResponse(
      Long requestId,
      String requestType,
      String targetType,
      Long targetId,
      String purpose,
      String scope,
      String status,
      Long beneficiaryOperatorId,
      OffsetDateTime createdAt) {}

  @Schema(description = "원문 접근 요청 목록 항목")
  public record RawAccessItem(
      Long requestId,
      String requestType,
      String targetType,
      Long targetId,
      String purpose,
      String scope,
      String status,
      Long requestedByOperatorId,
      String requestedByName,
      Long beneficiaryOperatorId,
      String approvedByName,
      OffsetDateTime approvedAt,
      OffsetDateTime expiresAt,
      Long grantId,
      OffsetDateTime createdAt) {}

  @Schema(description = "원문 접근 요청 상세")
  public record RawAccessDetailResponse(
      Long requestId,
      String requestType,
      String targetType,
      Long targetId,
      String purpose,
      String scope,
      String status,
      Long requestedByOperatorId,
      String requestedByName,
      Long beneficiaryOperatorId,
      String approvedByName,
      OffsetDateTime approvedAt,
      OffsetDateTime expiresAt,
      Long grantId,
      OffsetDateTime createdAt,
      String basisType,
      String basisNote,
      Long linkedInquiryId,
      Long officeId,
      String rejectedByName,
      OffsetDateTime rejectedAt,
      String rejectReason,
      RawAccessGrantDetail grant) {}

  public record RawAccessGrantDetail(
      Long grantId,
      OffsetDateTime executedAt,
      OffsetDateTime expiresAt,
      Long fileSize,
      String fileSha256,
      OffsetDateTime downloadedAt,
      OffsetDateTime revokedAt,
      String revokedByName,
      String revokeReason) {}

  @Schema(description = "원문 접근 승인 결과")
  public record RawAccessApproveResponse(
      Long requestId,
      String requestType,
      String status,
      OffsetDateTime approvedAt,
      OffsetDateTime expiresAt) {}

  @Schema(description = "원문 접근 거절 결과")
  public record RawAccessRejectResponse(Long requestId, String status, OffsetDateTime rejectedAt) {}

  @Schema(description = "원문 접근 실행 결과")
  public record RawAccessExecuteResponse(
      Long grantId,
      Long approvalRequestId,
      String requestType,
      OffsetDateTime executedAt,
      OffsetDateTime expiresAt,
      Long fileSize,
      String fileSha256,
      String downloadPath) {}

  @Schema(description = "원문 접근 회수 결과")
  public record RawAccessRevokeResponse(
      Long grantId, OffsetDateTime revokedAt, Long revokedByOperatorId) {}
}
