package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 지원 문의·기업 도입 문의 운영 처리 응답이다(OPS-05). */
public final class OpsSupportResponses {

  private OpsSupportResponses() {}

  @Schema(description = "운영 문의 목록 항목")
  public record OpsSupportInquiryItem(
      Long inquiryId,
      Long officeId,
      String officeName,
      Long authorUserId,
      String authorDisplayName,
      String category,
      String status,
      String priority,
      Long assignedOperatorId,
      String assignedOperatorName,
      OffsetDateTime responseDueAt,
      OffsetDateTime answeredAt,
      OffsetDateTime closedAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      long version) {}

  @Schema(description = "운영 문의 메시지")
  public record OpsSupportMessage(
      Long messageId,
      String visibility,
      String authorType,
      String authorDisplayName,
      String content,
      OffsetDateTime createdAt) {}

  @Schema(description = "운영 문의 첨부")
  public record OpsSupportAttachment(
      Long attachmentId,
      Long messageId,
      String visibility,
      String fileName,
      String mediaType,
      long sizeBytes,
      String scanStatus,
      OffsetDateTime createdAt) {}

  @Schema(description = "운영 문의 상세")
  public record OpsSupportInquiryDetailResponse(
      Long inquiryId,
      Long officeId,
      String officeName,
      Long authorUserId,
      String authorDisplayName,
      String category,
      String status,
      String priority,
      Long assignedOperatorId,
      String assignedOperatorName,
      OffsetDateTime responseDueAt,
      OffsetDateTime answeredAt,
      OffsetDateTime closedAt,
      OffsetDateTime createdAt,
      String content,
      String requestScope,
      List<OpsSupportMessage> messages,
      List<OpsSupportAttachment> attachments,
      OffsetDateTime updatedAt,
      long version) {}

  @Schema(description = "문의 공개 답변 결과")
  public record OpsSupportInquiryAnswerResponse(
      Long inquiryId,
      String status,
      String category,
      String priority,
      OffsetDateTime responseDueAt,
      OffsetDateTime answeredAt,
      OffsetDateTime closedAt,
      OffsetDateTime createdAt,
      List<OpsSupportMessage> messages,
      List<OpsSupportAttachment> attachments,
      Long officeId,
      OffsetDateTime updatedAt,
      long version) {}

  @Schema(description = "문의 담당 배정 결과")
  public record OpsSupportInquiryAssignResponse(
      Long inquiryId,
      String status,
      Long assigneeOperatorId,
      Long officeId,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      long version) {}

  @Schema(description = "문의 내부 메모 결과")
  public record OpsSupportInquiryNoteResponse(
      Long messageId,
      Long inquiryId,
      String visibility,
      String content,
      OffsetDateTime createdAt,
      Long officeId,
      OffsetDateTime updatedAt,
      long version) {}

  @Schema(description = "운영자 문의 첨부 등록 결과")
  public record OpsSupportInquiryAttachmentResponse(
      Long attachmentId,
      Long inquiryId,
      Long messageId,
      String scanStatus,
      String fileName,
      String mediaType,
      long sizeBytes,
      OffsetDateTime createdAt) {}

  @Schema(description = "문의 우선순위 변경 결과")
  public record OpsSupportInquiryPriorityResponse(
      Long inquiryId,
      String priority,
      OffsetDateTime responseDueAt,
      OffsetDateTime updatedAt,
      long version) {}

  @Schema(description = "기업 도입 문의 목록 항목")
  public record OpsEnterpriseLeadItem(
      Long leadId,
      String companyName,
      String status,
      Long assignedOperatorId,
      String assignedOperatorName,
      String contactName,
      int branchCount,
      int expectedSeats,
      boolean migrationNeeded,
      Long quoteAmount,
      OffsetDateTime quoteExpiresAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      long version) {}

  @Schema(description = "기업 도입 견적 버전")
  public record OpsEnterpriseQuote(
      Long quoteId,
      int versionNo,
      int officeCount,
      int seatCount,
      long quoteAmount,
      long taxAmount,
      String serviceScope,
      LocalDate deliveryDueAt,
      String paymentMethod,
      String refundTerms,
      OffsetDateTime issuedAt,
      String issuedByOperatorName,
      OffsetDateTime expiresAt,
      boolean expired,
      OffsetDateTime acceptedAt,
      OffsetDateTime rejectedAt,
      OffsetDateTime supersededAt,
      Long billingInvoiceId) {}

  @Schema(description = "기업 도입 진행 이력")
  public record OpsEnterpriseLeadHistory(
      String eventType,
      String fromStatus,
      String toStatus,
      String actorType,
      String actorOperatorName,
      String assignedOperatorName,
      Long quoteId,
      String reason,
      OffsetDateTime createdAt) {}

  @Schema(description = "기업 도입 문의 상세")
  public record OpsEnterpriseLeadDetailResponse(
      Long leadId,
      String companyName,
      String contactName,
      String contactPhone,
      String contactEmail,
      int branchCount,
      int expectedSeats,
      boolean migrationNeeded,
      String content,
      String status,
      Long assignedOperatorId,
      String assignedOperatorName,
      String contractReference,
      OffsetDateTime contractedAt,
      String closedReason,
      String closedNote,
      String closedFromStatus,
      OffsetDateTime closedAt,
      List<OpsEnterpriseQuote> quotes,
      List<OpsEnterpriseLeadHistory> history,
      List<String> allowedActions,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      long version) {}

  @Schema(description = "기업 도입 견적 발행 결과")
  public record EnterpriseLeadQuoteResponse(
      Long quoteId,
      int quoteVersion,
      OffsetDateTime quoteExpiresAt,
      Long leadId,
      String leadStatus,
      Long supersededQuoteId,
      OffsetDateTime updatedAt,
      long version) {}

  @Schema(description = "기업 도입 문의 담당 배정 결과")
  public record OpsEnterpriseLeadAssignResponse(
      Long leadId,
      String status,
      Long assignedOperatorId,
      String assignedOperatorName,
      OffsetDateTime updatedAt,
      long version) {}

  @Schema(description = "기업 도입 상담 시작 결과")
  public record OpsEnterpriseLeadConsultationStartResponse(
      Long leadId, String status, OffsetDateTime updatedAt, long version) {}

  @Schema(description = "기업 도입 계약 기록 결과")
  public record OpsEnterpriseLeadContractResponse(
      Long leadId,
      String status,
      String contractReference,
      OffsetDateTime contractedAt,
      OffsetDateTime updatedAt,
      long version) {}

  @Schema(description = "기업 도입 문의 종료 결과")
  public record OpsEnterpriseLeadCloseResponse(
      Long leadId,
      String status,
      String closedReason,
      String closedFromStatus,
      OffsetDateTime closedAt,
      OffsetDateTime updatedAt,
      long version) {}

  @Schema(description = "기업 도입 문의 재개 결과")
  public record OpsEnterpriseLeadReopenResponse(
      Long leadId,
      String status,
      Long assignedOperatorId,
      OffsetDateTime updatedAt,
      long version) {}
}
