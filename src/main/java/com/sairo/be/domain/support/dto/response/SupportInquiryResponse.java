package com.sairo.be.domain.support.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

public record SupportInquiryResponse(
    Long inquiryId,
    String status,
    String category,
    String priority,
    @Schema(nullable = true) String assignedOperatorName,
    OffsetDateTime responseDueAt,
    @Schema(nullable = true) OffsetDateTime answeredAt,
    @Schema(nullable = true) OffsetDateTime closedAt,
    OffsetDateTime createdAt,
    @Schema(description = "최초 문의 본문") String content,
    @Schema(nullable = true, description = "요청자가 적은 요청 범위") String requestScope,
    List<MessageItem> messages,
    List<AttachmentItem> attachments,
    @Schema(nullable = true) Long officeId,
    OffsetDateTime updatedAt,
    Long version) {

  public record MessageItem(
      Long messageId,
      String visibility,
      String content,
      String authorType,
      @Schema(nullable = true) String authorDisplayName,
      OffsetDateTime createdAt) {}

  public record AttachmentItem(
      Long attachmentId,
      String fileName,
      String mediaType,
      long sizeBytes,
      String scanStatus,
      OffsetDateTime createdAt,
      @Schema(nullable = true) Long messageId) {}
}
