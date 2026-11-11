package com.sairo.be.domain.support.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record SupportInquiryAttachmentResponse(
    Long attachmentId,
    Long inquiryId,
    @Schema(nullable = true) Long messageId,
    String scanStatus,
    String fileName,
    String mediaType,
    long sizeBytes,
    OffsetDateTime createdAt) {}
