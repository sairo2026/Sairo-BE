package com.sairo.be.domain.billing.dto.request;

import com.sairo.be.domain.billing.entity.RefundEventType;
import com.sairo.be.domain.billing.entity.RefundReasonCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "환불 신청 요청. 대표는 청구서와 사유 분류만 보낸다.")
public record RefundRequestCreateRequest(
    @NotNull Long invoiceId,
    @NotNull RefundReasonCode reasonCode,
    RefundEventType reasonEventType,
    Long reasonEventId) {}
