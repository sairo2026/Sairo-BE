package com.sairo.be.domain.billing.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "환불 신청 접수 결과")
public record RefundRequestCreateResponse(Long refundId, String status) {}
