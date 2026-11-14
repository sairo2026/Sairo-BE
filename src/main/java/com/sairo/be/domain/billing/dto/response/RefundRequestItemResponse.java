package com.sairo.be.domain.billing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "환불 신청 목록 항목")
public record RefundRequestItemResponse(
    Long refundId,
    Long invoiceId,
    String status,
    String reasonCode,
    Long refundAmount,
    OffsetDateTime createdAt) {}
