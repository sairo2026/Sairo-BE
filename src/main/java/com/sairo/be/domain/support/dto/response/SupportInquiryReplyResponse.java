package com.sairo.be.domain.support.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record SupportInquiryReplyResponse(
    Long messageId,
    Long inquiryId,
    String visibility,
    String status,
    String content,
    OffsetDateTime createdAt,
    @Schema(nullable = true) Long officeId,
    OffsetDateTime updatedAt,
    Long version) {}
